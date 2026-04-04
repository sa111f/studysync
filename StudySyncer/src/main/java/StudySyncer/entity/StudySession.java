package StudySyncer.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "study_sessions")
public class StudySession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(length = 200)
    private String materialName;

    private int durationMinutes;

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
        createdAt = LocalDateTime.now();
    }

    // ── Getters ───────────────────────────────────────────
    public Long          getId()              { return id; }
    public User          getUser()            { return user; }
    public String        getMaterialName()    { return materialName; }
    public int           getDurationMinutes() { return durationMinutes; }
    public String        getTimerMode()       { return timerMode; }
    public boolean       isCompleted()        { return completed; }
    public LocalDateTime getStartedAt()       { return startedAt; }
    public LocalDateTime getCompletedAt()     { return completedAt; }
    public LocalDate     getStudyDate()       { return studyDate; }
    public LocalDateTime getCreatedAt()       { return createdAt; }

    // ── Setters ───────────────────────────────────────────
    public void setUser(User u)                  { this.user = u; }
    public void setMaterialName(String n)        { this.materialName = n; }
    public void setDurationMinutes(int d)        { this.durationMinutes = d; }
    public void setTimerMode(String m)           { this.timerMode = m; }
    public void setCompleted(boolean c)          { this.completed = c; }
    public void setStartedAt(LocalDateTime t)    { this.startedAt = t; }
    public void setCompletedAt(LocalDateTime t)  { this.completedAt = t; }
    public void setStudyDate(LocalDate d)        { this.studyDate = d; }
}
