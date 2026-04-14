package StudySyncer.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Single authoritative row per user holding the full timer state.
 *
 * Zero-downtime schema note:
 *   Columns added after the table already existed use columnDefinition with
 *   DEFAULTs so Hibernate's ddl-auto=update can add them to existing rows
 *   without requiring a manual migration.  Once the DB has picked up each
 *   default, pre-existing users get sane starting state on their next request.
 */
@Entity
public class TimerProgress {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(optional = false)
    @JoinColumn(name = "user_id", unique = true)
    private User user;

    // ── Legacy fields (kept for backward-compat with /save /load DTO) ──
    private String mode;          // "pomodoro" or "countdown"
    private int totalSeconds;     // configured duration of current phase
    private int sessionCount;     // 1-based index of the current pomodoro

    // ── Full-state fields ──────────────────────────────────────────────
    /** 'pomodoro' | 'shortbreak' | 'longbreak' | 'countdown' */
    @Column(name = "phase", length = 20,
            columnDefinition = "VARCHAR(20) DEFAULT 'pomodoro'")
    private String phase;

    @Column(name = "pomodoro_minutes",
            columnDefinition = "INT DEFAULT 25")
    private int pomodoroMinutes;

    @Column(name = "short_break_minutes",
            columnDefinition = "INT DEFAULT 5")
    private int shortBreakMinutes;

    @Column(name = "long_break_minutes",
            columnDefinition = "INT DEFAULT 15")
    private int longBreakMinutes;

    /** Legacy column — retained so ddl-auto doesn't drop it and so /load still works.
     *  Meaning now mirrors plannedSeconds - elapsedBaseSeconds. */
    @Column(name = "remaining_seconds",
            columnDefinition = "INT DEFAULT 1500")
    private int remainingSeconds;

    @Column(name = "running",
            columnDefinition = "BOOLEAN DEFAULT FALSE")
    private boolean running;

    @Column(name = "paused",
            columnDefinition = "BOOLEAN DEFAULT FALSE")
    private boolean paused;

    /** Legacy column — kept for backward compat; no longer read. */
    @Column(name = "run_end_at_ms")
    private Long runEndAtMs;

    // ── Overtime-aware fields ─────────────────────────────────────────
    /** Planned duration of the current phase (seconds). */
    @Column(name = "planned_seconds",
            columnDefinition = "INT DEFAULT 1500")
    private int plannedSeconds;

    /**
     * Elapsed seconds accumulated from previous running segments of the
     * current session (before the active segment). Authoritative when
     * !running. While running, the true elapsed is this + (now - runStartAtMs).
     */
    @Column(name = "elapsed_base_seconds",
            columnDefinition = "INT DEFAULT 0")
    private int elapsedBaseSeconds;

    /** Epoch ms at which the current running segment began. Null when stopped/paused. */
    @Column(name = "run_start_at_ms")
    private Long runStartAtMs;

    private LocalDateTime updatedAt;

    // ── Getters ───────────────────────────────────────────────────────

    public Long getId()                  { return id; }
    public User getUser()                { return user; }
    public String getMode()              { return mode; }
    public int getTotalSeconds()         { return totalSeconds; }
    public int getSessionCount()         { return sessionCount; }
    public String getPhase()             { return phase; }
    public int getPomodoroMinutes()      { return pomodoroMinutes; }
    public int getShortBreakMinutes()    { return shortBreakMinutes; }
    public int getLongBreakMinutes()     { return longBreakMinutes; }
    public int getRemainingSeconds()     { return remainingSeconds; }
    public boolean isRunning()           { return running; }
    public boolean isPaused()            { return paused; }
    public Long getRunEndAtMs()          { return runEndAtMs; }
    public int getPlannedSeconds()       { return plannedSeconds; }
    public int getElapsedBaseSeconds()   { return elapsedBaseSeconds; }
    public Long getRunStartAtMs()        { return runStartAtMs; }
    public LocalDateTime getUpdatedAt()  { return updatedAt; }

    // ── Setters ───────────────────────────────────────────────────────

    public void setUser(User user)                      { this.user = user; }
    public void setMode(String mode)                    { this.mode = mode; }
    public void setTotalSeconds(int s)                  { this.totalSeconds = s; }
    public void setSessionCount(int c)                  { this.sessionCount = c; }
    public void setPhase(String phase)                  { this.phase = phase; }
    public void setPomodoroMinutes(int m)               { this.pomodoroMinutes = m; }
    public void setShortBreakMinutes(int m)             { this.shortBreakMinutes = m; }
    public void setLongBreakMinutes(int m)              { this.longBreakMinutes = m; }
    public void setRemainingSeconds(int s)              { this.remainingSeconds = s; }
    public void setRunning(boolean r)                   { this.running = r; }
    public void setPaused(boolean p)                    { this.paused = p; }
    public void setRunEndAtMs(Long ms)                  { this.runEndAtMs = ms; }
    public void setPlannedSeconds(int s)                { this.plannedSeconds = s; }
    public void setElapsedBaseSeconds(int s)            { this.elapsedBaseSeconds = s; }
    public void setRunStartAtMs(Long ms)                { this.runStartAtMs = ms; }
    public void setUpdatedAt(LocalDateTime t)           { this.updatedAt = t; }
}
