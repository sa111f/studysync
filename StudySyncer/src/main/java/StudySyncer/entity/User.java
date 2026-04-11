package StudySyncer.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

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
}
