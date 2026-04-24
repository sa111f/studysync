package StudySyncer.dto;

import StudySyncer.entity.Priority;
import StudySyncer.entity.Task;
import StudySyncer.entity.TaskStatus;
import StudySyncer.entity.TaskType;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Response body for all task read endpoints and the payload returned from
 * POST / PUT / PATCH.
 *
 * The `overdue` flag is derived server-side so the UI never has to reason
 * about timezones — the service passes "today" as computed from the
 * authenticated user's stored timezone (mirrors DailyGoalController).
 */
public class TaskResponse {

    private Long          id;
    private String        title;
    private String        notes;
    private LocalDate     dueDate;
    private String        course;
    private TaskType      type;
    private Priority      priority;
    private TaskStatus    status;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
    private boolean       overdue;

    /**
     * Total time the user has logged against this task, in SECONDS.
     * Phase 1 stored durations as minutes on StudySession; this field returns
     * minutes × 60 so the client can format with sub-minute precision for
     * short sessions ("30s logged" for a test run) and "2h 15m" for longer ones.
     */
    private long          secondsLogged;

    public TaskResponse() { /* Jackson */ }

    /**
     * Factory that maps an entity to a response, computing the derived
     * `overdue` flag against the supplied `today` and attaching `secondsLogged`
     * if the caller has pre-aggregated the value. Call sites that don't need
     * logged-time (future unrelated features) may pass 0.
     */
    public static TaskResponse from(Task t, LocalDate today, long secondsLogged) {
        TaskResponse r = new TaskResponse();
        r.id          = t.getId();
        r.title       = t.getTitle();
        r.notes       = t.getNotes();
        r.dueDate     = t.getDueDate();
        r.course      = t.getCourse();
        r.type        = t.getType();
        r.priority    = t.getPriority();
        r.status      = t.getStatus();
        r.createdAt   = t.getCreatedAt();
        r.completedAt = t.getCompletedAt();
        r.overdue     = t.getStatus() != TaskStatus.COMPLETED
                     && t.getDueDate() != null
                     && t.getDueDate().isBefore(today);
        r.secondsLogged = Math.max(0L, secondsLogged);
        return r;
    }

    /** Back-compat overload for call sites that don't need logged time. */
    public static TaskResponse from(Task t, LocalDate today) {
        return from(t, today, 0L);
    }

    // ── Getters ───────────────────────────────────────────
    public Long          getId()          { return id; }
    public String        getTitle()       { return title; }
    public String        getNotes()       { return notes; }
    public LocalDate     getDueDate()     { return dueDate; }
    public String        getCourse()      { return course; }
    public TaskType      getType()        { return type; }
    public Priority      getPriority()    { return priority; }
    public TaskStatus    getStatus()      { return status; }
    public LocalDateTime getCreatedAt()   { return createdAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public boolean       isOverdue()      { return overdue; }
    public long          getSecondsLogged() { return secondsLogged; }
}
