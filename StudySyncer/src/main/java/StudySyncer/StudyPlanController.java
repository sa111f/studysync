package StudySyncer;

import StudySyncer.dto.StudyPlanRequest;
import StudySyncer.dto.StudyPlanResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * STUD-20: Thin controller — delegates to StudyPlanService.
 *          Swap StudyPlanService for an AI-backed implementation without
 *          changing this class.
 * STUD-24: Returns structured error responses instead of unhandled 500s.
 */
@RestController
@RequestMapping("/api")
public class StudyPlanController {

    private final StudyPlanService studyPlanService;

    public StudyPlanController(StudyPlanService studyPlanService) {
        this.studyPlanService = studyPlanService;
    }

    @PostMapping("/study-plan")
    public ResponseEntity<?> generatePlan(@RequestBody StudyPlanRequest request) {
        // STUD-24: validate before processing
        if (request == null || request.getSubjects() == null || request.getSubjects().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "No subjects provided."));
        }

        try {
            StudyPlanResponse response = studyPlanService.generatePlan(request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to generate study plan. Please try again."));
        }
    }
}
