package StudySyncer.dto;

import StudySyncer.entity.Priority;
import StudySyncer.entity.TaskStatus;
import StudySyncer.entity.TaskType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Request body for POST /api/tasks and PUT /api/tasks/{id}.
 *
 * Bean Validation policy:
 *   - title   : @NotBlank, 1–200 chars (always required)
 *   - dueDate : @NotNull (always required)
 *   - notes   : optional, max 2000 chars
 *   - course  : optional, max 100 chars
 *   - type / priority / status :
 *       Optional on CREATE (service fills defaults OTHER / MEDIUM / NOT_STARTED).
 *       REQUIRED on UPDATE — the controller's PUT handler asserts non-null
 *       after @Valid and returns 400 with a specific message if any is missing.
 *       This asymmetry keeps the same DTO usable for both verbs.
 */
public class TaskRequest {

    @NotBlank(message = "Title is required.")
    @Size(max = 200, message = "Title must be at most 200 characters.")
    private String title;

    @Size(max = 2000, message = "Notes must be at most 2000 characters.")
    private String notes;

    @NotNull(message = "Due date is required.")
    private LocalDate dueDate;

    @Size(max = 100, message = "Course must be at most 100 characters.")
    private String course;

    private TaskType type;

    private Priority priority;

    private TaskStatus status;

    // ── Getters ───────────────────────────────────────────
    public String     getTitle()    { return title; }
    public String     getNotes()    { return notes; }
    public LocalDate  getDueDate()  { return dueDate; }
    public String     getCourse()   { return course; }
    public TaskType   getType()     { return type; }
    public Priority   getPriority() { return priority; }
    public TaskStatus getStatus()   { return status; }

    // ── Setters ───────────────────────────────────────────
    public void setTitle(String title)        { this.title = title; }
    public void setNotes(String notes)        { this.notes = notes; }
    public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }
    public void setCourse(String course)      { this.course = course; }
    public void setType(TaskType type)        { this.type = type; }
    public void setPriority(Priority p)       { this.priority = p; }
    public void setStatus(TaskStatus status)  { this.status = status; }
}
