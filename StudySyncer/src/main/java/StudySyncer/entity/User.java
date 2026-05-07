package StudySyncer.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 50)
    private String username;

    /**
     * Nullable — Google-only accounts (future) may arrive without a pre-set email.
     * All local (password) accounts always have an email.
     */
    @Column(unique = true, length = 120)
    private String email;

    /**
     * BCrypt hash. Null only for OAuth-only accounts.
     * No length cap needed — BCrypt output is always 60 chars.
     */
    @Column(name = "password_hash")
    private String passwordHash;

    /**
     * OAuth2 provider used to create this account, e.g. "google".
     * Null for local (email/password) accounts.
     */
    @Column(name = "oauth_provider", length = 32)
    private String oauthProvider;

    /**
     * Whether the email address has been verified.
     * Local accounts are set to TRUE immediately (email verification not yet implemented).
     *
     * columnDefinition = "BOOLEAN DEFAULT TRUE" supplies the DB-level DEFAULT.
     * nullable = false owns the NOT NULL constraint — do NOT put NOT NULL inside
     * columnDefinition too, or Hibernate 6 generates "BOOLEAN DEFAULT TRUE NOT NULL NOT NULL".
     */
    @Column(name = "email_verified", nullable = false,
            columnDefinition = "BOOLEAN DEFAULT TRUE")
    private boolean emailVerified = true;

    /**
     * Persistent accountability email saved by the user via the "Set Email" button.
     * Used as the default recipient for goal-reached and missed-goal emails.
     * Falls back to this.email if null.
     */
    @Column(name = "accountability_email", length = 255)
    private String accountabilityEmail;

    /**
     * IANA timezone string detected from the user's browser (e.g. "America/Toronto").
     * Updated automatically on dashboard load, goal save, and session save.
     * Used by DailyGoalRolloverService to determine when midnight has passed for this user.
     * Defaults to "America/Toronto" when null.
     */
    @Column(name = "timezone", length = 50)
    private String timezone;

    // ── Phase 8: notification preferences ─────────────────────────────────────
    //
    // Three opt-in email types — each toggled independently. All require
    // accountabilityEmail to be set (no fallback to login email, per spec).
    // All times are user-local via the existing `timezone` field above.

    /** Daily digest opt-in. */
    @Column(name = "digest_enabled", nullable = false,
            columnDefinition = "boolean NOT NULL DEFAULT false")
    private boolean digestEnabled = false;

    /** Wall-clock time to send the digest in the user's timezone. */
    @Column(name = "digest_local_time")
    private LocalTime digestLocalTime = LocalTime.of(8, 0);

    /** Overdue reminder opt-in (fires only when a task went overdue yesterday). */
    @Column(name = "overdue_reminder_enabled", nullable = false,
            columnDefinition = "boolean NOT NULL DEFAULT false")
    private boolean overdueReminderEnabled = false;

    @Column(name = "overdue_reminder_local_time")
    private LocalTime overdueReminderLocalTime = LocalTime.of(20, 0);

    /** Exam reminder opt-in — thresholds are hardcoded at 7/3/1 days. */
    @Column(name = "exam_reminder_enabled", nullable = false,
            columnDefinition = "boolean NOT NULL DEFAULT false")
    private boolean examReminderEnabled = false;

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

    public Long          getId()                    { return id; }
    public String        getUsername()              { return username; }
    public String        getEmail()                 { return email; }
    public String        getPasswordHash()          { return passwordHash; }
    public String        getOauthProvider()         { return oauthProvider; }
    public boolean       isEmailVerified()          { return emailVerified; }
    public String        getAccountabilityEmail()   { return accountabilityEmail; }
    public String        getTimezone()              { return timezone; }
    public boolean       isDigestEnabled()          { return digestEnabled; }
    public LocalTime     getDigestLocalTime()       { return digestLocalTime; }
    public boolean       isOverdueReminderEnabled() { return overdueReminderEnabled; }
    public LocalTime     getOverdueReminderLocalTime() { return overdueReminderLocalTime; }
    public boolean       isExamReminderEnabled()    { return examReminderEnabled; }
    public LocalDateTime getCreatedAt()             { return createdAt; }
    public LocalDateTime getUpdatedAt()             { return updatedAt; }

    // ── Setters ───────────────────────────────────────────

    public void setUsername(String username)                    { this.username = username; }
    public void setEmail(String email)                          { this.email = email; }
    public void setPasswordHash(String passwordHash)            { this.passwordHash = passwordHash; }
    public void setOauthProvider(String oauthProvider)          { this.oauthProvider = oauthProvider; }
    public void setEmailVerified(boolean verified)              { this.emailVerified = verified; }
    public void setAccountabilityEmail(String email)            { this.accountabilityEmail = email; }
    public void setTimezone(String timezone)                    { this.timezone = timezone; }
    public void setDigestEnabled(boolean b)                     { this.digestEnabled = b; }
    public void setDigestLocalTime(LocalTime t)                 { this.digestLocalTime = t; }
    public void setOverdueReminderEnabled(boolean b)            { this.overdueReminderEnabled = b; }
    public void setOverdueReminderLocalTime(LocalTime t)        { this.overdueReminderLocalTime = t; }
    public void setExamReminderEnabled(boolean b)               { this.examReminderEnabled = b; }
}
