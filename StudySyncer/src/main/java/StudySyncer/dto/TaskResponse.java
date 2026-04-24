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

    public TaskResponse() { /* Jackson */ }

    /**
     * Factory that maps an entity to a response, computing the derived
     * `overdue` flag against the supplied `today`.
     *
     * @param today the user-local date the request arrived on — passed in
     *              (rather than computed from the entity) so the same instance
     *              is reused across a batch map call.
     */
    public static TaskResponse from(Task t, LocalDate today) {
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
        return r;
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
}
