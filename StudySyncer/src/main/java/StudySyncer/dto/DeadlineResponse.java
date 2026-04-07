package StudySyncer.dto;

public class DeadlineResponse {

    private Long   id;
    private String title;
    private String subject;
    private String type;
    private String dueDate;      // "2025-05-01"
    private String dueTime;      // "14:00" or null
    private String notes;
    private String urgency;      // overdue | today | tomorrow | soon | upcoming | later
    private int    daysRemaining; // negative = overdue
    private int    priorityScore;
    private String createdAt;

    // ── Getters ───────────────────────────────────────────
    public Long   getId()            { return id; }
    public String getTitle()         { return title; }
    public String getSubject()       { return subject; }
    public String getType()          { return type; }
    public String getDueDate()       { return dueDate; }
    public String getDueTime()       { return dueTime; }
    public String getNotes()         { return notes; }
    public String getUrgency()       { return urgency; }
    public int    getDaysRemaining() { return daysRemaining; }
    public int    getPriorityScore() { return priorityScore; }
    public String getCreatedAt()     { return createdAt; }

    // ── Setters ───────────────────────────────────────────
    public void setId(Long id)                      { this.id = id; }
    public void setTitle(String title)              { this.title = title; }
    public void setSubject(String subject)          { this.subject = subject; }
    public void setType(String type)                { this.type = type; }
    public void setDueDate(String dueDate)          { this.dueDate = dueDate; }
    public void setDueTime(String dueTime)          { this.dueTime = dueTime; }
    public void setNotes(String notes)              { this.notes = notes; }
    public void setUrgency(String urgency)          { this.urgency = urgency; }
    public void setDaysRemaining(int d)             { this.daysRemaining = d; }
    public void setPriorityScore(int s)             { this.priorityScore = s; }
    public void setCreatedAt(String createdAt)      { this.createdAt = createdAt; }
}
