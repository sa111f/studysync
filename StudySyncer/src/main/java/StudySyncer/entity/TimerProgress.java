package StudySyncer.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
public class TimerProgress {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(optional = false)
    @JoinColumn(name = "user_id", unique = true)
    private User user;

    private String mode;          // "pomodoro" or "countdown"
    private int totalSeconds;     // configured duration
    private int sessionCount;     // completed pomodoro sessions

    private LocalDateTime updatedAt;

    public Long getId()                     { return id; }
    public User getUser()                   { return user; }
    public String getMode()                 { return mode; }
    public int getTotalSeconds()            { return totalSeconds; }
    public int getSessionCount()            { return sessionCount; }
    public LocalDateTime getUpdatedAt()     { return updatedAt; }

    public void setUser(User user)          { this.user = user; }
    public void setMode(String mode)        { this.mode = mode; }
    public void setTotalSeconds(int s)      { this.totalSeconds = s; }
    public void setSessionCount(int c)      { this.sessionCount = c; }
    public void setUpdatedAt(LocalDateTime t) { this.updatedAt = t; }
}
