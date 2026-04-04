package StudySyncer;

import StudySyncer.dto.SavePlanItem;
import StudySyncer.dto.SavePlanRequest;
import StudySyncer.dto.SubjectPlan;
import StudySyncer.dto.StudyPlanResponse;
import StudySyncer.entity.SavedPlan;
import StudySyncer.entity.SavedSubject;
import StudySyncer.entity.User;
import StudySyncer.repository.SavedPlanRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * STUD-33 / STUD-35: Saves and loads study plans per user.
 * Each user has at most one saved plan — saving replaces the previous one.
 */
@Service
public class PlanSaveService {

    private final SavedPlanRepository savedPlanRepository;

    public PlanSaveService(SavedPlanRepository savedPlanRepository) {
        this.savedPlanRepository = savedPlanRepository;
    }

    @Transactional
    public void savePlan(User user, SavePlanRequest request) {
        // Replace any existing plan for this user
        savedPlanRepository.findByUser(user).ifPresent(existing -> {
            savedPlanRepository.delete(existing);
            savedPlanRepository.flush();
        });

        SavedPlan plan = new SavedPlan();
        plan.setUser(user);
        plan.setWeeklyTotalHours(request.getWeeklyTotalHours());
        plan.setSavedAt(LocalDateTime.now());

        List<SavedSubject> subjects = request.getSubjectPlans().stream().map(item -> {
            SavedSubject s = new SavedSubject();
            s.setPlan(plan);
            s.setName(item.getName());
            s.setDifficulty(item.getDifficulty());
            s.setWeeklyHours(item.getRecommendedWeeklyHours());
            s.setDailyHours(item.getRecommendedDailyHours());
            s.setReason(item.getReason());
            s.setTopicNotes(item.getTopicNotes());
            return s;
        }).collect(Collectors.toList());

        plan.setSubjects(subjects);
        savedPlanRepository.save(plan);
    }

    /**
     * Returns the saved plan in the same shape as StudyPlanResponse
     * so renderStudyPlan() works without changes.
     */
    public Optional<StudyPlanResponse> loadPlan(User user) {
        return savedPlanRepository.findByUser(user).map(plan -> {
            List<SubjectPlan> items = plan.getSubjects().stream().map(s ->
                    new SubjectPlan(
                            s.getName(),
                            s.getDifficulty(),
                            s.getWeeklyHours(),
                            s.getDailyHours(),
                            s.getTopicNotes(),
                            s.getReason()
                    )
            ).collect(Collectors.toList());

            return new StudyPlanResponse(plan.getWeeklyTotalHours(), items);
        });
    }
}
