package StudySyncer;

import StudySyncer.dto.SaveSessionDto;
import StudySyncer.entity.User;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Controller
public class TrackerController {

    private static final Logger log = LoggerFactory.getLogger(TrackerController.class);

    private final TrackerService   trackerService;
    private final UserService      userService;
    private final DailyGoalService dailyGoalService;

    public TrackerController(TrackerService trackerService, UserService userService,
                             DailyGoalService dailyGoalService) {
        this.trackerService   = trackerService;
        this.userService      = userService;
        this.dailyGoalService = dailyGoalService;
    }

    // ── Page ──────────────────────────────────────────────

    @GetMapping("/tracker")
    public String trackerPage(HttpSession session) {
        Long userId = AuthController.resolveUserId(session);
        if (userId != null) {
            log.debug("[TRACKER] Page load — session userId={}", userId);
        } else {
            log.debug("[TRACKER] Page load — no active session (guest view)");
        }
        return "tracker";
    }

    // ── REST ──────────────────────────────────────────────

    /** POST /api/tracker/sessions — persist a completed timer session */
    @PostMapping("/api/tracker/sessions")
    @ResponseBody
    public ResponseEntity<?> saveSession(@RequestBody SaveSessionDto dto, HttpSession session) {
        User user = resolveUser(session);
        if (user == null) return ResponseEntity.status(401).body(Map.of("error", "Not logged in."));
        trackerService.saveSession(user,
                dto.getMaterialName(), dto.getDurationMinutes(),
                dto.getTimerMode(), dto.isCompleted());
        // Only count truly completed sessions toward the daily goal
        if (dto.isCompleted()) {
            dailyGoalService.addCompletedMinutes(user, dto.getDurationMinutes());
        }
        return ResponseEntity.ok(Map.of("message", "Session saved."));
    }

    /** GET /api/tracker/summary?range=week&offset=0 */
    @GetMapping("/api/tracker/summary")
    @ResponseBody
    public ResponseEntity<?> summary(
            @RequestParam(defaultValue = "week") String range,
            @RequestParam(defaultValue = "0")    int    offset,
            HttpSession session) {
        User user = resolveUser(session);
        if (user == null) return ResponseEntity.status(401).body(Map.of("error", "Not logged in."));
        return ResponseEntity.ok(trackerService.getSummary(user, range, offset));
    }

    /** GET /api/tracker/chart?range=week&offset=0 */
    @GetMapping("/api/tracker/chart")
    @ResponseBody
    public ResponseEntity<?> chart(
            @RequestParam(defaultValue = "week") String range,
            @RequestParam(defaultValue = "0")    int    offset,
            HttpSession session) {
        User user = resolveUser(session);
        if (user == null) return ResponseEntity.status(401).body(Map.of("error", "Not logged in."));
        return ResponseEntity.ok(trackerService.getChartData(user, range, offset));
    }

    /** GET /api/tracker/materials?range=week&offset=0 */
    @GetMapping("/api/tracker/materials")
    @ResponseBody
    public ResponseEntity<?> materials(
            @RequestParam(defaultValue = "week") String range,
            @RequestParam(defaultValue = "0")    int    offset,
            HttpSession session) {
        User user = resolveUser(session);
        if (user == null) return ResponseEntity.status(401).body(Map.of("error", "Not logged in."));
        return ResponseEntity.ok(trackerService.getMaterialBreakdown(user, range, offset));
    }

    /** GET /api/tracker/sessions?range=week&offset=0 */
    @GetMapping("/api/tracker/sessions")
    @ResponseBody
    public ResponseEntity<?> sessions(
            @RequestParam(defaultValue = "week") String range,
            @RequestParam(defaultValue = "0")    int    offset,
            HttpSession session) {
        User user = resolveUser(session);
        if (user == null) return ResponseEntity.status(401).body(Map.of("error", "Not logged in."));
        return ResponseEntity.ok(trackerService.getSessions(user, range, offset));
    }

    // ── Helper ────────────────────────────────────────────

    private User resolveUser(HttpSession session) {
        Long id = AuthController.resolveUserId(session);
        if (id == null) return null;
        return userService.findById(id).orElse(null);
    }
}
