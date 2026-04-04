package StudySyncer.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
public class SavedPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    private double weeklyTotalHours;

    private LocalDateTime savedAt;

    @OneToMany(mappedBy = "plan", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SavedSubject> subjects = new ArrayList<>();

    public Long getId()                            { return id; }
    public User getUser()                          { return user; }
    public double getWeeklyTotalHours()            { return weeklyTotalHours; }
    public LocalDateTime getSavedAt()              { return savedAt; }
    public List<SavedSubject> getSubjects()        { return subjects; }

    public void setUser(User user)                 { this.user = user; }
    public void setWeeklyTotalHours(double h)      { this.weeklyTotalHours = h; }
    public void setSavedAt(LocalDateTime t)        { this.savedAt = t; }
    public void setSubjects(List<SavedSubject> s)  { this.subjects = s; }
}
