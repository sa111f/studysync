package StudySyncer;

import StudySyncer.dto.StudyEstimationResponse;
import StudySyncer.dto.StudyMaterialRequest;
import StudySyncer.dto.StudyMaterialResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * STUD-69/71: Accepts study material content, returns structured time options.
 *
 * POST /api/study-material/estimate
 *   Request : { title?: string, content: string }
 *   Response: { lightMinutes, standardMinutes, deepMinutes, reason }
 *
 * Delegates to StudyEstimationService so rule-based and AI paths are shared.
 * Structured to accept real AI integration by swapping the service implementation.
 */
@RestController
@RequestMapping("/api/study-material")
public class StudyMaterialController {

    private final StudyEstimationService estimationService;

    public StudyMaterialController(StudyEstimationService estimationService) {
        this.estimationService = estimationService;
    }

    /** STUD-69/71: estimate study time from pasted notes or extracted PDF text */
    @PostMapping("/estimate")
    public ResponseEntity<?> estimate(@RequestBody StudyMaterialRequest request) {

        // STUD-68: validate content is present
        if (request == null
                || request.getContent() == null
                || request.getContent().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "No study material provided."));
        }

        try {
            // STUD-70/77: pass title and PDF metadata for richer AI prompt context;
            // difficulty fixed at "medium" — removed from user-facing UI.
            StudyEstimationResponse est =
                    estimationService.estimate(request.getContent(), "medium", request.getTitle(),
                            request.getPageCount(), request.getDocumentType());

            // STUD-71: map internal response to the new structured field names
            int wordCount = request.getContent().trim().split("\\s+").length;
            StudyMaterialResponse response = new StudyMaterialResponse(
                    est.getMinMinutes(),
                    est.getRecommendedMinutes(),
                    est.getMaxMinutes(),
                    est.getRationale(),
                    est.getEstimateSource(),
                    wordCount,
                    request.getPageCount()
            );

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Estimation failed. Please try again."));
        }
    }
}
