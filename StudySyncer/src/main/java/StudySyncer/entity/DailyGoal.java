package StudySyncer.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "daily_goals",
       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "goal_date"}))
public class DailyGoal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "goal_date", nullable = false)
    private LocalDate goalDate;

    /** Target minutes for this day. 0 means no explicit goal set. */
    @Column(name = "goal_minutes", nullable = false)
    private int goalMinutes = 0;

    /** Accumulated minutes from truly completed sessions on this day. */
    @Column(name = "completed_minutes", nullable = false)
    private int completedMinutes = 0;

    // ── Phase 4: Accountability SMS settings ─────────────────────────────────

    @Column(name = "notification_enabled", nullable = false)
    private boolean notificationEnabled = false;

    /** Phone number to send the end-of-day SMS to (E.164 preferred, e.g. +12125551234). */
    @Column(name = "accountability_phone", length = 30)
    private String accountabilityPhone;

    /** Optional display name for the recipient (UI only — not used in the SMS body). */
    @Column(name = "contact_name", length = 100)
    private String contactName;

    /**
     * User must tick a consent checkbox confirming the recipient agreed to receive
     * StudySyncer messages. The scheduler will NOT send without this being true.
     */
    @Column(name = "consent_confirmed", nullable = false)
    private boolean consentConfirmed = false;

    // ── Phase 4: Send result (written by the scheduler after dispatch) ────────

    @Column(name = "notification_sent", nullable = false)
    private boolean notificationSent = false;

    @Column(name = "notification_sent_at")
    private LocalDateTime notificationSentAt;

    /** Exact message body that was sent — stored for audit/debugging. */
    @Column(name = "notification_message", length = 300)
    private String notificationMessage;

    /**
     * Which kind of notification was sent: "SUCCESS" (goal reached, sent immediately)
     * or "FAILURE" (goal missed, sent by end-of-day scheduler).
     * Null until a notification is actually dispatched.
     */
    @Column(name = "notification_type", length = 10)
    private String notificationType;

    // ── Phase 5: Email accountability settings ───────────────────────────────

    /**
     * Optional email address to send the missed-goal alert to.
     * If blank, EmailService falls back to the user's registered email,
     * then to ALERT_TO_EMAIL.
     */
    @Column(name = "accountability_email", length = 255)
    private String accountabilityEmail;

    // ── Email tracking ────────────────────────────────────────────────────────

    /**
     * True after the "goal reached" success email has been sent for this day.
     * Set immediately when the user crosses the goal threshold during the day.
     * Also used by the scheduler as a guard: if this is true, no missed-goal
     * email is needed at end of day.
     *
     * columnDefinition is required so Hibernate generates DEFAULT false when
     * adding this column to an existing PostgreSQL table.
     */
    @Column(name = "goal_reached_email_sent", nullable = false,
            columnDefinition = "boolean NOT NULL DEFAULT false")
    private boolean goalReachedEmailSent = false;

    /** Timestamp of when the goal-reached email was sent. */
    @Column(name = "goal_reached_email_sent_at")
    private LocalDateTime goalReachedEmailSentAt;

    /**
     * True after the missed-goal email has been sent at end of day.
     * Used as an idempotency guard — prevents duplicate sends.
     *
     * columnDefinition is required for the same PostgreSQL ADD COLUMN reason above.
     */
    @Column(name = "email_alert_sent", nullable = false,
            columnDefinition = "boolean NOT NULL DEFAULT false")
    private boolean emailAlertSent = false;

    /** Timestamp of when the missed-goal email was sent. */
    @Column(name = "email_alert_sent_at")
    private LocalDateTime emailAlertSentAt;

    /**
     * JPA optimistic-lock version counter.
     *
     * Prevents a race condition where two concurrent threads (e.g. the 15-minute
     * scheduler and a simultaneous dashboard load) both read emailAlertSent=false,
     * both decide to send the missed-goal email, and both succeed — sending two
     * emails to the user.
     *
     * With @Version: the first thread to commit increments the version.  The second
     * thread tries to UPDATE with the old version number, the DB rejects it, and
     * Spring throws ObjectOptimisticLockingFailureException, which the rollover
     * service catches and logs.  Only one email is ever sent per goal per day.
     *
     * Hibernate auto-adds the column (ddl-auto=update) on first restart after
     * this field is introduced.  Existing rows receive version=0.
     */
    @Version
    @Column(name = "version")
    private long version;

    // ── Audit ─────────────────────────────────────────────────────────────────

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // ── Getters ───────────────────────────────────────────

    public Long          getId()                  { return id; }
    public User          getUser()                { return user; }
    public LocalDate     getGoalDate()            { return goalDate; }
    public int           getGoalMinutes()         { return goalMinutes; }
    public int           getCompletedMinutes()    { return completedMinutes; }
    public boolean       isNotificationEnabled()  { return notificationEnabled; }
    public String        getAccountabilityPhone() { return accountabilityPhone; }
    public String        getContactName()         { return contactName; }
    public boolean       isConsentConfirmed()     { return consentConfirmed; }
    public boolean       isNotificationSent()     { return notificationSent; }
    public LocalDateTime getNotificationSentAt()  { return notificationSentAt; }
    public String        getNotificationMessage() { return notificationMessage; }
    public String        getNotificationType()    { return notificationType; }
    public String        getAccountabilityEmail()       { return accountabilityEmail; }
    public boolean       isGoalReachedEmailSent()      { return goalReachedEmailSent; }
    public LocalDateTime getGoalReachedEmailSentAt()   { return goalReachedEmailSentAt; }
    public boolean       isEmailAlertSent()            { return emailAlertSent; }
    public LocalDateTime getEmailAlertSentAt()         { return emailAlertSentAt; }
    public long          getVersion()                  { return version; }
    public LocalDateTime getCreatedAt()           { return createdAt; }
    public LocalDateTime getUpdatedAt()           { return updatedAt; }

    // ── Setters ───────────────────────────────────────────

    public void setUser(User u)                              { this.user = u; }
    public void setGoalDate(LocalDate d)                     { this.goalDate = d; }
    public void setGoalMinutes(int m)                        { this.goalMinutes = Math.max(0, m); }
    public void setCompletedMinutes(int m)                   { this.completedMinutes = Math.max(0, m); }
    public void setNotificationEnabled(boolean enabled)      { this.notificationEnabled = enabled; }
    public void setAccountabilityPhone(String phone)         { this.accountabilityPhone = phone; }
    public void setContactName(String name)                  { this.contactName = name; }
    public void setConsentConfirmed(boolean confirmed)       { this.consentConfirmed = confirmed; }
    public void setNotificationSent(boolean sent)            { this.notificationSent = sent; }
    public void setNotificationSentAt(LocalDateTime sentAt)  { this.notificationSentAt = sentAt; }
    public void setNotificationMessage(String message)       { this.notificationMessage = message; }
    public void setNotificationType(String type)             { this.notificationType = type; }
    public void setAccountabilityEmail(String email)              { this.accountabilityEmail = email; }
    public void setGoalReachedEmailSent(boolean sent)             { this.goalReachedEmailSent = sent; }
    public void setGoalReachedEmailSentAt(LocalDateTime sentAt)   { this.goalReachedEmailSentAt = sentAt; }
    public void setEmailAlertSent(boolean sent)                   { this.emailAlertSent = sent; }
    public void setEmailAlertSentAt(LocalDateTime sentAt)         { this.emailAlertSentAt = sentAt; }
}
