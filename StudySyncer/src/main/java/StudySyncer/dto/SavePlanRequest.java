package StudySyncer.dto;

import java.util.List;

/** Mirrors StudyPlanResponse shape so the frontend can POST the plan it just received. */
public class SavePlanRequest {
    private double weeklyTotalHours;
    private List<SavePlanItem> subjectPlans;

    public double getWeeklyTotalHours()             { return weeklyTotalHours; }
    public List<SavePlanItem> getSubjectPlans()     { return subjectPlans; }
    public void setWeeklyTotalHours(double h)       { this.weeklyTotalHours = h; }
    public void setSubjectPlans(List<SavePlanItem> s) { this.subjectPlans = s; }
}
