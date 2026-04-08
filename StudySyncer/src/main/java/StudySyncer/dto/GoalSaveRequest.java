package StudySyncer.dto;

/**
 * Request body for POST /api/daily-goal
 *
 * Covers goal-minutes and email accountability settings in a single save call
 * so the UI only needs one request.
 */
public class GoalSaveRequest {

    /** Target study minutes for the day (1–1440). */
    private int goalMinutes;

    /** Whether the user wants an email if they miss today's goal. */
    private boolean notificationEnabled;

    /**
     * Optional email address to send the missed-goal alert to.
     * If blank and notificationEnabled=true, the alert goes to the user's
     * registered email address (or ALERT_TO_EMAIL as a last resort).
     */
    private String accountabilityEmail;

    // ── Getters ───────────────────────────────────────────

    public int     getGoalMinutes()          { return goalMinutes; }
    public boolean isNotificationEnabled()   { return notificationEnabled; }
    public String  getAccountabilityEmail()  { return accountabilityEmail; }

    // ── Setters ───────────────────────────────────────────

    public void setGoalMinutes(int m)                { this.goalMinutes = m; }
    public void setNotificationEnabled(boolean e)    { this.notificationEnabled = e; }
    public void setAccountabilityEmail(String email) { this.accountabilityEmail = email; }
}
