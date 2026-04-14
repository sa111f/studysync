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

    private final TrackerService          trackerService;
    private final UserService             userService;
    private final DailyGoalRolloverService rolloverService;

    public TrackerController(TrackerService trackerService,
                             UserService userService,
                             DailyGoalRolloverService rolloverService) {
        this.trackerService  = trackerService;
        this.userService     = userService;
        this.rolloverService = rolloverService;
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

        // Rollover check: if the user was studying past midnight, ensure any missed-goal
        // email for the previous day is sent before we credit the session to today's goal.
        // Uses the user's stored timezone for the day-boundary calculation.
        java.time.ZoneId zone = rolloverService.resolveUserZone(user);
        rolloverService.processRolloverForUser(user, zone);

        trackerService.saveSession(user,
                dto.getMaterialName(), dto.getDurationMinutes(),
                dto.getPlannedMinutes(), dto.getOvertimeMinutes(),
                dto.getTimerMode(), dto.isCompleted());
        // Goal-progress sync and goal-reached email check are both handled inside
        // trackerService.saveSession — do NOT add a duplicate call here.
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
