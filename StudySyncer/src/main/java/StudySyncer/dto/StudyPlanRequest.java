package StudySyncer.dto;

import java.util.List;

public class StudyPlanRequest {

    private List<SubjectRequest> subjects;

    public List<SubjectRequest> getSubjects() { return subjects; }
    public void setSubjects(List<SubjectRequest> subjects) { this.subjects = subjects; }
}
