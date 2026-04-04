package StudySyncer.dto;

import java.util.List;

public class StudyPlanResponse {

    private double weeklyTotalHours;
    private List<SubjectPlan> subjectPlans;

    public StudyPlanResponse(double weeklyTotalHours, List<SubjectPlan> subjectPlans) {
        this.weeklyTotalHours = weeklyTotalHours;
        this.subjectPlans = subjectPlans;
    }

    public double getWeeklyTotalHours()        { return weeklyTotalHours; }
    public List<SubjectPlan> getSubjectPlans() { return subjectPlans; }
}
