package StudySyncer;

import StudySyncer.dto.SavePlanRequest;
import StudySyncer.dto.StudyPlanResponse;
import StudySyncer.entity.User;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

/**
 * STUD-33 — Save generated study plan for logged-in users.
 * STUD-35 — Load the most-recent saved plan on return.
 */
@RestController
@RequestMapping("/api/plans")
public class PlanSaveController {

    private final PlanSaveService planSaveService;
    private final UserService userService;

    public PlanSaveController(PlanSaveService planSaveService, UserService userService) {
        this.planSaveService = planSaveService;
        this.userService     = userService;
    }

    /** POST /api/plans/save — saves (replaces) the current plan for the logged-in user */
    @PostMapping("/save")
    public ResponseEntity<?> save(@RequestBody SavePlanRequest request, HttpSession session) {
        User user = resolveUser(session);
        if (user == null) return ResponseEntity.status(401).body(Map.of("error", "Not logged in."));

        if (request.getSubjectPlans() == null || request.getSubjectPlans().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No subjects to save."));
        }

        planSaveService.savePlan(user, request);
        return ResponseEntity.ok(Map.of("message", "Plan saved."));
    }

    /** GET /api/plans/latest — returns the saved plan, or 404 if none */
    @GetMapping("/latest")
    public ResponseEntity<?> latest(HttpSession session) {
        User user = resolveUser(session);
        if (user == null) return ResponseEntity.status(401).body(Map.of("error", "Not logged in."));

        Optional<StudyPlanResponse> plan = planSaveService.loadPlan(user);
        return plan.<ResponseEntity<?>>map(ResponseEntity::ok)
                   .orElse(ResponseEntity.notFound().build());
    }

    private User resolveUser(HttpSession session) {
        Long id = AuthController.resolveUserId(session);
        if (id == null) return null;
        return userService.findById(id).orElse(null);
    }
}
