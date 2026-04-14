package StudySyncer.dto;

/**
 * Wire format for the full timer state.
 *
 * Overtime-aware model
 * ────────────────────
 *   plannedSeconds   — the configured length of the current phase
 *   elapsedBase      — elapsed seconds accumulated from prior running
 *                      segments of this session; authoritative while !running
 *   runStartAtMs     — epoch ms when the current running segment began
 *
 *   The client combines these with its live clock to compute elapsed,
 *   remaining, isOvertime, and overtimeSeconds. Reaching zero no longer
 *   finalizes a session; only an explicit /stop does.
 *
 * Legacy fields (mode, totalSeconds, sessionCount, remainingSeconds,
 * runEndAtMs) are populated so the old /api/timer/save and /load handlers
 * continue to work for any client that hasn't been upgraded.
 *
 * serverNowMs is set on every read so the frontend can compute wall-clock
 * drift and correct its local display.
 */
public class TimerStateDto {

    // Legacy
    private String mode;
    private int    totalSeconds;
    private int    sessionCount;

    // Full state
    private String  phase;
    private int     pomodoroMinutes;
    private int     shortBreakMinutes;
    private int     longBreakMinutes;
    private int     remainingSeconds;
    private boolean running;
    private boolean paused;
    private Long    runEndAtMs;
    private long    serverNowMs;

    // Overtime-aware fields
    private int     plannedSeconds;
    private int     elapsedBase;
    private Long    runStartAtMs;

    // Set by /stop and /skip responses so the frontend knows what was just committed
    private String  completedPhase;
    private Integer completedSeconds;
    private Boolean shouldLogStudy;

    // ── Legacy getters / setters ──────────────────────────────────────
    public String getMode()              { return mode; }
    public int    getTotalSeconds()      { return totalSeconds; }
    public int    getSessionCount()      { return sessionCount; }
    public void   setMode(String mode)   { this.mode = mode; }
    public void   setTotalSeconds(int s) { this.totalSeconds = s; }
    public void   setSessionCount(int c) { this.sessionCount = c; }

    // ── Full-state getters / setters ──────────────────────────────────
    public String  getPhase()                 { return phase; }
    public int     getPomodoroMinutes()       { return pomodoroMinutes; }
    public int     getShortBreakMinutes()     { return shortBreakMinutes; }
    public int     getLongBreakMinutes()      { return longBreakMinutes; }
    public int     getRemainingSeconds()      { return remainingSeconds; }
    public boolean isRunning()                { return running; }
    public boolean isPaused()                 { return paused; }
    public Long    getRunEndAtMs()            { return runEndAtMs; }
    public long    getServerNowMs()           { return serverNowMs; }
    public int     getPlannedSeconds()        { return plannedSeconds; }
    public int     getElapsedBase()           { return elapsedBase; }
    public Long    getRunStartAtMs()          { return runStartAtMs; }
    public String  getCompletedPhase()        { return completedPhase; }
    public Integer getCompletedSeconds()      { return completedSeconds; }
    public Boolean getShouldLogStudy()        { return shouldLogStudy; }

    public void setPhase(String p)              { this.phase = p; }
    public void setPomodoroMinutes(int m)       { this.pomodoroMinutes = m; }
    public void setShortBreakMinutes(int m)     { this.shortBreakMinutes = m; }
    public void setLongBreakMinutes(int m)      { this.longBreakMinutes = m; }
    public void setRemainingSeconds(int s)      { this.remainingSeconds = s; }
    public void setRunning(boolean r)           { this.running = r; }
    public void setPaused(boolean p)            { this.paused = p; }
    public void setRunEndAtMs(Long ms)          { this.runEndAtMs = ms; }
    public void setServerNowMs(long ms)         { this.serverNowMs = ms; }
    public void setPlannedSeconds(int s)        { this.plannedSeconds = s; }
    public void setElapsedBase(int s)           { this.elapsedBase = s; }
    public void setRunStartAtMs(Long ms)        { this.runStartAtMs = ms; }
    public void setCompletedPhase(String p)     { this.completedPhase = p; }
    public void setCompletedSeconds(Integer s)  { this.completedSeconds = s; }
    public void setShouldLogStudy(Boolean b)    { this.shouldLogStudy = b; }
}
