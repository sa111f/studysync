package StudySyncer.dto;

public class SubjectRequest {

    private String name;
    private String difficulty;
    private double workload;
    private String notes;

    public String getName()       { return name; }
    public String getDifficulty() { return difficulty; }
    public double getWorkload()   { return workload; }
    public String getNotes()      { return notes; }

    public void setName(String name)             { this.name = name; }
    public void setDifficulty(String difficulty) { this.difficulty = difficulty; }
    public void setWorkload(double workload)      { this.workload = workload; }
    public void setNotes(String notes)           { this.notes = notes; }
}
