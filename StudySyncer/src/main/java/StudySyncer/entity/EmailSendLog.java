package StudySyncer.entity;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Audit + idempotency row for every email StudySyncer actually sends.
 *
 * Idempotency key: (userId, emailType, referenceId) is unique. The
 * scheduler checks this before sending and inserts on success — so a
 * crash during email send followed by a retry on the next tick won't
 * double-deliver.
 *
 * referenceId semantics by emailType:
 *   DIGEST, OVERDUE_REMINDER         → date code YYYYMMDD in user's local tz
 *   EXAM_REMINDER_{7D,3D,1D}         → Exam.id
 *   GOAL_REACHED, GOAL_MISSED        → date code YYYYMMDD of the goalDate
 *
 * We're writing the whole day code (not just 7-day window) so distinct
 * days always get distinct rows regardless of timezone edge cases.
 */
@Entity
@Table(name = "email_send_log",
       uniqueConstraints = @UniqueConstraint(
               name = "uq_email_send_log_user_type_ref",
               columnNames = {"user_id", "email_type", "reference_id"}),
       indexes = {
               @Index(name = "idx_email_send_log_user_sent", columnList = "user_id, sent_at"),
               @Index(name = "idx_email_send_log_user_type", columnList = "user_id, email_type")
       })
public class EmailSendLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "email_type", nullable = false, length = 30)
    private EmailType emailType;

    /**
     * Semantic-per-type reference — see class docstring.
     * Nullable because some future type might not need one, but every
     * type in Phase 8 DOES populate it.
     */
    @Column(name = "reference_id")
    private Long referenceId;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    @PrePersist
    void onCreate() {
        if (sentAt == null) sentAt = Instant.now();
    }

    // ── Getters ───────────────────────────────────────────
    public Long      getId()          { return id; }
    public User      getUser()        { return user; }
    public EmailType getEmailType()   { return emailType; }
    public Long      getReferenceId() { return referenceId; }
    public Instant   getSentAt()      { return sentAt; }

    // ── Setters ───────────────────────────────────────────
    public void setUser(User user)            { this.user = user; }
    public void setEmailType(EmailType type)  { this.emailType = type; }
    public void setReferenceId(Long refId)    { this.referenceId = refId; }
    public void setSentAt(Instant when)       { this.sentAt = when; }
}
