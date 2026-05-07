package StudySyncer.dto;

import StudySyncer.entity.TaskType;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One item extracted from a syllabus. Discriminated by {@link #kind}:
 *   - "task" → uses {@link #dueDate} + {@link #taskType}
 *   - "exam" → uses {@link #dateTime}   (no taskType)
 *
 * Course + material are shared across both kinds. Not-applicable fields
 * are left null and omitted from the API response via Jackson defaults.
 */
public class SyllabusItem {

    /** "task" or "exam" — lowercase to match the AI tool schema's enum. */
    private String    kind;
    private String    title;
    private String    course;

    // Task-only
    private LocalDate dueDate;
    private TaskType  taskType;

    // Exam-only
    private Instant   dateTime;

    // Optional on either
    private String    material;

    public SyllabusItem() { /* Jackson */ }

    public static SyllabusItem task(String title, LocalDate dueDate,
                                    TaskType type, String course, String material) {
        SyllabusItem i = new SyllabusItem();
        i.kind     = "task";
        i.title    = title;
        i.dueDate  = dueDate;
        i.taskType = type;
        i.course   = course;
        i.material = material;
        return i;
    }

    public static SyllabusItem exam(String title, Instant dateTime,
                                    String course, String material) {
        SyllabusItem i = new SyllabusItem();
        i.kind     = "exam";
        i.title    = title;
        i.dateTime = dateTime;
        i.course   = course;
        i.material = material;
        return i;
    }

    // ── Getters ───────────────────────────────────────────
    public String    getKind()     { return kind; }
    public String    getTitle()    { return title; }
    public String    getCourse()   { return course; }
    public LocalDate getDueDate()  { return dueDate; }
    public TaskType  getTaskType() { return taskType; }
    public Instant   getDateTime() { return dateTime; }
    public String    getMaterial() { return material; }

    // ── Setters (Jackson) ─────────────────────────────────
    public void setKind(String k)        { this.kind = k; }
    public void setTitle(String t)       { this.title = t; }
    public void setCourse(String c)      { this.course = c; }
    public void setDueDate(LocalDate d)  { this.dueDate = d; }
    public void setTaskType(TaskType t)  { this.taskType = t; }
    public void setDateTime(Instant dt)  { this.dateTime = dt; }
    public void setMaterial(String m)    { this.material = m; }
}
