package StudySyncer.dto;

/**
 * Request body for POST /api/daily-goal
 * Covers goal-minutes (Phase 2) and accountability SMS settings (Phase 4)
 * in a single save call so the UI only needs one request.
 */
public class GoalSaveRequest {

    private int     goalMinutes;
    private boolean notificationEnabled;
    private String  accountabilityPhone;
    private String  contactName;
    private boolean consentConfirmed;

    // ── Getters ───────────────────────────────────────────

    public int     getGoalMinutes()         { return goalMinutes; }
    public boolean isNotificationEnabled()  { return notificationEnabled; }
    public String  getAccountabilityPhone() { return accountabilityPhone; }
    public String  getContactName()         { return contactName; }
    public boolean isConsentConfirmed()     { return consentConfirmed; }

    // ── Setters ───────────────────────────────────────────

    public void setGoalMinutes(int m)                  { this.goalMinutes = m; }
    public void setNotificationEnabled(boolean e)      { this.notificationEnabled = e; }
    public void setAccountabilityPhone(String phone)   { this.accountabilityPhone = phone; }
    public void setContactName(String name)            { this.contactName = name; }
    public void setConsentConfirmed(boolean confirmed) { this.consentConfirmed = confirmed; }
}
