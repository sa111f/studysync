package StudySyncer.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Entity
@Table(name = "deadlines")
public class Deadline {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 100)
    private String subject;

    /** exam | quiz | assignment | lab | project | other */
    @Column(length = 50)
    private String type;

    @Column(nullable = false)
    private LocalDate dueDate;

    private LocalTime dueTime;

    @Column(length = 500)
    private String notes;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // ── Getters ───────────────────────────────────────────
    public Long          getId()        { return id; }
    public User          getUser()      { return user; }
    public String        getTitle()     { return title; }
    public String        getSubject()   { return subject; }
    public String        getType()      { return type; }
    public LocalDate     getDueDate()   { return dueDate; }
    public LocalTime     getDueTime()   { return dueTime; }
    public String        getNotes()     { return notes; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    // ── Setters ───────────────────────────────────────────
    public void setUser(User user)          { this.user = user; }
    public void setTitle(String title)      { this.title = title; }
    public void setSubject(String subject)  { this.subject = subject; }
    public void setType(String type)        { this.type = type; }
    public void setDueDate(LocalDate d)     { this.dueDate = d; }
    public void setDueTime(LocalTime t)     { this.dueTime = t; }
    public void setNotes(String notes)      { this.notes = notes; }
}
