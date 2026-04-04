package StudySyncer.dto;

public class SavePlanItem {
    private String name;
    private String difficulty;
    private double recommendedWeeklyHours;
    private double recommendedDailyHours;
    private String topicNotes;
    private String reason;

    public String getName()                        { return name; }
    public String getDifficulty()                  { return difficulty; }
    public double getRecommendedWeeklyHours()      { return recommendedWeeklyHours; }
    public double getRecommendedDailyHours()       { return recommendedDailyHours; }
    public String getTopicNotes()                  { return topicNotes; }
    public String getReason()                      { return reason; }

    public void setName(String name)               { this.name = name; }
    public void setDifficulty(String d)            { this.difficulty = d; }
    public void setRecommendedWeeklyHours(double h){ this.recommendedWeeklyHours = h; }
    public void setRecommendedDailyHours(double h) { this.recommendedDailyHours = h; }
    public void setTopicNotes(String notes)        { this.topicNotes = notes; }
    public void setReason(String reason)           { this.reason = reason; }
}
