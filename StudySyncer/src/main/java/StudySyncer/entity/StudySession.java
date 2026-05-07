package StudySyncer.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Entity
@Table(name = "study_sessions",
       indexes = {
           @Index(name = "idx_sessions_user_task", columnList = "user_id, task_id")
       })
public class StudySession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * Soft reference to Task.id — no FK, no JPA relationship.
     * If a task is deleted, sessions keep the id but it points nowhere;
     * the Tracker Detail view renders such rows without a task badge.
     * Nullable: sessions predate Phase 1, and a session may be logged
     * with "No task selected".
     */
    @Column(name = "task_id")
    private Long taskId;

    @Column(length = 200)
    private String materialName;

    /** Actual minutes the user studied (may exceed plannedMinutes in overtime). */
    private int durationMinutes;

    /** Configured phase length when the session started — 0 for legacy rows. */
    @Column(name = "planned_minutes",
            columnDefinition = "INT DEFAULT 0")
    private int plannedMinutes;

    /** max(0, duration - planned). Persisted so reports don't recompute on every read. */
    @Column(name = "overtime_minutes",
            columnDefinition = "INT DEFAULT 0")
    private int overtimeMinutes;

    @Column(length = 50)
    private String timerMode;

    private boolean completed;

    /** When the session was estimated to have started (completedAt − durationMinutes). */
    private LocalDateTime startedAt;

    @Column(nullable = false)
    private LocalDateTime completedAt;

    /** Denormalised date column — makes range queries simple and fast. */
    @Column(nullable = false)
    private LocalDate studyDate;

    /** Row-creation timestamp — set once by @PrePersist, never updated. */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now(ZoneId.of("America/Toronto"));
    }

    // ── Getters ───────────────────────────────────────────
    public Long          getId()              { return id; }
    public User          getUser()            { return user; }
    public Long          getTaskId()          { return taskId; }
    public String        getMaterialName()    { return materialName; }
    public int           getDurationMinutes() { return durationMinutes; }
    public int           getPlannedMinutes()  { return plannedMinutes; }
    public int           getOvertimeMinutes() { return overtimeMinutes; }
    public String        getTimerMode()       { return timerMode; }
    public boolean       isCompleted()        { return completed; }
    public LocalDateTime getStartedAt()       { return startedAt; }
    public LocalDateTime getCompletedAt()     { return completedAt; }
    public LocalDate     getStudyDate()       { return studyDate; }
    public LocalDateTime getCreatedAt()       { return createdAt; }

    // ── Setters ───────────────────────────────────────────
    public void setUser(User u)                  { this.user = u; }
    public void setTaskId(Long taskId)           { this.taskId = taskId; }
    public void setMaterialName(String n)        { this.materialName = n; }
    public void setDurationMinutes(int d)        { this.durationMinutes = d; }
    public void setPlannedMinutes(int m)         { this.plannedMinutes = m; }
    public void setOvertimeMinutes(int m)        { this.overtimeMinutes = m; }
    public void setTimerMode(String m)           { this.timerMode = m; }
    public void setCompleted(boolean c)          { this.completed = c; }
    public void setStartedAt(LocalDateTime t)    { this.startedAt = t; }
    public void setCompletedAt(LocalDateTime t)  { this.completedAt = t; }
    public void setStudyDate(LocalDate d)        { this.studyDate = d; }
}
