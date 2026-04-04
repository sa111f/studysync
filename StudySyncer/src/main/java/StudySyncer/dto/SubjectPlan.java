package StudySyncer.dto;

/**
 * STUD-23: recommendedDailyHours
 * STUD-21: topicNotes
 * STUD-28: reason — why this subject received its hour allocation
 */
public class SubjectPlan {

    private String name;
    private String difficulty;
    private double recommendedWeeklyHours;
    private double recommendedDailyHours;
    private String topicNotes;
    private String reason;

    public SubjectPlan(String name, String difficulty,
                       double recommendedWeeklyHours, double recommendedDailyHours,
                       String topicNotes, String reason) {
        this.name                  = name;
        this.difficulty            = difficulty;
        this.recommendedWeeklyHours = recommendedWeeklyHours;
        this.recommendedDailyHours  = recommendedDailyHours;
        this.topicNotes            = topicNotes;
        this.reason                = reason;
    }

    public String getName()                   { return name; }
    public String getDifficulty()             { return difficulty; }
    public double getRecommendedWeeklyHours() { return recommendedWeeklyHours; }
    public double getRecommendedDailyHours()  { return recommendedDailyHours; }
    public String getTopicNotes()             { return topicNotes; }
    public String getReason()                 { return reason; }
}
