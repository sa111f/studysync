package StudySyncer.dto;

public class DeadlineRequest {

    private String title;
    private String subject;
    private String type;
    private String dueDate;   // ISO-8601 date: "2025-05-01"
    private String dueTime;   // ISO-8601 time: "14:00" — optional
    private String notes;

    public String getTitle()   { return title; }
    public String getSubject() { return subject; }
    public String getType()    { return type; }
    public String getDueDate() { return dueDate; }
    public String getDueTime() { return dueTime; }
    public String getNotes()   { return notes; }

    public void setTitle(String title)     { this.title = title; }
    public void setSubject(String subject) { this.subject = subject; }
    public void setType(String type)       { this.type = type; }
    public void setDueDate(String dueDate) { this.dueDate = dueDate; }
    public void setDueTime(String dueTime) { this.dueTime = dueTime; }
    public void setNotes(String notes)     { this.notes = notes; }
}
