package StudySyncer.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Exam entity — captures a scheduled exam with a full start date + time.
 *
 * NOTE: Spec originally called for Instant on dateTime. Matched existing
 * project convention (User.createdAt / StudySession.completedAt / DailyGoal
 * all use LocalDateTime) per Phase 1.1 instructions ("Match whatever you
 * find. Don't introduce new patterns.").
 */
@Entity
@Table(name = "exams",
       indexes = {
           @Index(name = "idx_exams_user_datetime", columnList = "user_id, date_time"),
           @Index(name = "idx_exams_user_created",  columnList = "user_id, created_at")
       })
public class Exam {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 100)
    private String course;

    @Column(name = "date_time", nullable = false)
    private LocalDateTime dateTime;

    @Column(length = 2000)
    private String material;

    @Column(length = 1000)
    private String notes;

    @Column(length = 200)
    private String location;

    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    // ── Getters ───────────────────────────────────────────
    public Long          getId()        { return id; }
    public User          getUser()      { return user; }
    public String        getTitle()     { return title; }
    public String        getCourse()    { return course; }
    public LocalDateTime getDateTime()  { return dateTime; }
    public String        getMaterial()  { return material; }
    public String        getNotes()     { return notes; }
    public String        getLocation()  { return location; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    // ── Setters ───────────────────────────────────────────
    public void setUser(User user)           { this.user = user; }
    public void setTitle(String title)       { this.title = title; }
    public void setCourse(String course)     { this.course = course; }
    public void setDateTime(LocalDateTime t) { this.dateTime = t; }
    public void setMaterial(String material) { this.material = material; }
    public void setNotes(String notes)       { this.notes = notes; }
    public void setLocation(String loc)      { this.location = loc; }
}
