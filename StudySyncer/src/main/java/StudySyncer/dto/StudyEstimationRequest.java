package StudySyncer.dto;

public class StudyEstimationRequest {
    private String text;
    private String difficulty;

    public String getText()       { return text; }
    public String getDifficulty() { return difficulty; }
    public void setText(String text)             { this.text = text; }
    public void setDifficulty(String difficulty) { this.difficulty = difficulty; }
}
