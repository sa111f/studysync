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

    /**
     * IANA timezone string detected by the browser (e.g. "America/Toronto").
     * Sent by the frontend alongside goal saves so the backend can update the
     * user's stored timezone and use it for rollover calculations.
     * Optional — if absent, the backend uses the user's stored timezone.
     */
    private String timezone;

    // ── Getters ───────────────────────────────────────────

    public int     getGoalMinutes()          { return goalMinutes; }
    public boolean isNotificationEnabled()   { return notificationEnabled; }
    public String  getAccountabilityEmail()  { return accountabilityEmail; }
    public String  getTimezone()             { return timezone; }

    // ── Setters ───────────────────────────────────────────

    public void setGoalMinutes(int m)                { this.goalMinutes = m; }
    public void setNotificationEnabled(boolean e)    { this.notificationEnabled = e; }
    public void setAccountabilityEmail(String email) { this.accountabilityEmail = email; }
    public void setTimezone(String timezone)          { this.timezone = timezone; }
}
