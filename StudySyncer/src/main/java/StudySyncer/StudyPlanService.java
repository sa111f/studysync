package StudySyncer;

import StudySyncer.dto.StudyPlanRequest;
import StudySyncer.dto.StudyPlanResponse;
import StudySyncer.dto.SubjectPlan;
import StudySyncer.dto.SubjectRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * STUD-20: Thin service layer — swap generatePlan() body for an AI call later.
 * STUD-21: Notes/syllabus depth adjusts the time estimate (up to +20%).
 * STUD-23: Daily hours = weeklyHours ÷ 5 study days.
 * STUD-28: buildReason() produces a human-readable explanation per subject.
 */
@Service
public class StudyPlanService {

    public StudyPlanResponse generatePlan(StudyPlanRequest request) {
        List<SubjectPlan> plans = new ArrayList<>();
        double total = 0;

        for (SubjectRequest subject : request.getSubjects()) {

            // Difficulty multiplier
            double multiplier = switch (subject.getDifficulty().toLowerCase()) {
                case "easy" -> 0.8;
                case "hard" -> 1.3;
                default     -> 1.0;
            };

            // STUD-21: notes depth adjusts estimate (up to +20%)
            String notes = (subject.getNotes() != null) ? subject.getNotes().trim() : "";
            double notesMultiplier = 1.0;
            if (!notes.isEmpty()) {
                notesMultiplier = 1.0 + Math.min(notes.length() / 500.0, 0.2);
            }

            double weeklyHours = round1dp(subject.getWorkload() * multiplier * notesMultiplier);
            double dailyHours  = round1dp(weeklyHours / 5.0);
            total += weeklyHours;

            // STUD-28: explain the allocation
            String reason = buildReason(
                    subject.getDifficulty(), !notes.isEmpty(), notesMultiplier);

            plans.add(new SubjectPlan(
                    subject.getName(),
                    subject.getDifficulty(),
                    weeklyHours,
                    dailyHours,
                    notes,
                    reason
            ));
        }

        return new StudyPlanResponse(round1dp(total), plans);
    }

    /**
     * STUD-28: Produces a one-sentence explanation of why a subject
     * received its particular hour allocation.
     */
    private String buildReason(String difficulty, boolean hasNotes, double notesMultiplier) {
        boolean hard    = difficulty.equalsIgnoreCase("hard");
        boolean easy    = difficulty.equalsIgnoreCase("easy");
        boolean boosted = hasNotes && notesMultiplier > 1.0;

        if (hard && boosted)
            return "Hard difficulty and a detailed syllabus increased recommended hours.";
        if (hard)
            return "Hard difficulty increased recommended hours.";
        if (easy && boosted)
            return "Easy difficulty reduced hours; detailed syllabus added some back.";
        if (easy)
            return "Easy difficulty reduced recommended hours.";
        if (boosted)
            return "Detailed syllabus added extra study time.";
        return "Based on stated workload at standard difficulty.";
    }

    private double round1dp(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
