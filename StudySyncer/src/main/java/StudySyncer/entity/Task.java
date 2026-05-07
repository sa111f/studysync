package StudySyncer.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "tasks",
       indexes = {
           @Index(name = "idx_tasks_user_due",     columnList = "user_id, due_date"),
           @Index(name = "idx_tasks_user_status",  columnList = "user_id, status"),
           @Index(name = "idx_tasks_user_created", columnList = "user_id, created_at")
       })
public class Task {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 2000)
    private String notes;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(length = 100)
    private String course;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TaskType type = TaskType.OTHER;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Priority priority = Priority.MEDIUM;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TaskStatus status = TaskStatus.NOT_STARTED;

    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (type     == null) type     = TaskType.OTHER;
        if (priority == null) priority = Priority.MEDIUM;
        if (status   == null) status   = TaskStatus.NOT_STARTED;
    }

    // ── Getters ───────────────────────────────────────────
    public Long          getId()          { return id; }
    public User          getUser()        { return user; }
    public String        getTitle()       { return title; }
    public String        getNotes()       { return notes; }
    public LocalDate     getDueDate()     { return dueDate; }
    public String        getCourse()      { return course; }
    public TaskType      getType()        { return type; }
    public Priority      getPriority()    { return priority; }
    public TaskStatus    getStatus()      { return status; }
    public LocalDateTime getCreatedAt()   { return createdAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }

    // ── Setters ───────────────────────────────────────────
    public void setUser(User user)             { this.user = user; }
    public void setTitle(String title)         { this.title = title; }
    public void setNotes(String notes)         { this.notes = notes; }
    public void setDueDate(LocalDate d)        { this.dueDate = d; }
    public void setCourse(String course)       { this.course = course; }
    public void setType(TaskType type)         { this.type = type; }
    public void setPriority(Priority p)        { this.priority = p; }
    public void setStatus(TaskStatus s)        { this.status = s; }
    public void setCompletedAt(LocalDateTime t){ this.completedAt = t; }
}
