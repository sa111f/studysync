package StudySyncer.entity;

import jakarta.persistence.*;

@Entity
public class SavedSubject {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "plan_id")
    private SavedPlan plan;

    private String name;
    private String difficulty;
    private double weeklyHours;
    private double dailyHours;

    @Column(length = 500)
    private String reason;

    @Column(length = 2000)
    private String topicNotes;

    public Long getId()                      { return id; }
    public SavedPlan getPlan()               { return plan; }
    public String getName()                  { return name; }
    public String getDifficulty()            { return difficulty; }
    public double getWeeklyHours()           { return weeklyHours; }
    public double getDailyHours()            { return dailyHours; }
    public String getReason()                { return reason; }
    public String getTopicNotes()            { return topicNotes; }

    public void setPlan(SavedPlan plan)      { this.plan = plan; }
    public void setName(String name)         { this.name = name; }
    public void setDifficulty(String d)      { this.difficulty = d; }
    public void setWeeklyHours(double h)     { this.weeklyHours = h; }
    public void setDailyHours(double h)      { this.dailyHours = h; }
    public void setReason(String reason)     { this.reason = reason; }
    public void setTopicNotes(String notes)  { this.topicNotes = notes; }
}
