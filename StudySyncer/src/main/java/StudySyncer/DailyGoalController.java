package StudySyncer;

import StudySyncer.dto.GoalSaveRequest;
import StudySyncer.entity.DailyGoal;
import StudySyncer.entity.User;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/daily-goal")
public class DailyGoalController {

    private static final Logger log = LoggerFactory.getLogger(DailyGoalController.class);

    private final DailyGoalService        dailyGoalService;
    private final UserService             userService;
    private final AccountabilityScheduler scheduler;

    public DailyGoalController(DailyGoalService dailyGoalService,
                                UserService userService,
                                AccountabilityScheduler scheduler) {
        this.dailyGoalService = dailyGoalService;
        this.userService      = userService;
        this.scheduler        = scheduler;
    }

    // ── GET /api/daily-goal/today ──────────────────────────────────────────────

    /** Returns current goal, progress, and email accountability settings for today. */
    @GetMapping("/today")
    public ResponseEntity<?> getToday(HttpSession session) {
        User user = resolveUser(session);
        if (user == null) return ResponseEntity.status(401).body(Map.of("error", "Not logged in."));
        Optional<DailyGoal> opt = dailyGoalService.getTodayGoal(user);
        return ResponseEntity.ok(toMap(opt.orElse(null), user));
    }

    // ── POST /api/daily-goal ───────────────────────────────────────────────────

    /**
     * Saves today's goal minutes + email accountability settings in one call.
     */
    @PostMapping
    public ResponseEntity<?> saveGoal(@RequestBody GoalSaveRequest req, HttpSession session) {
        User user = resolveUser(session);
        if (user == null) return ResponseEntity.status(401).body(Map.of("error", "Not logged in."));
        try {
            DailyGoal goal = dailyGoalService.saveGoalAndSettings(user, req);
            log.info("[GOAL] Saved — userId={} goalMinutes={} emailEnabled={}",
                    user.getId(), goal.getGoalMinutes(), goal.isNotificationEnabled());
            return ResponseEntity.ok(toMap(goal, user));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── POST /api/daily-goal/trigger-notifications (testing only) ─────────────

    /**
     * Manually triggers the end-of-day scheduler right now.
     * Useful during development/testing so you don't have to wait until 23:59.
     */
    @PostMapping("/trigger-notifications")
    public ResponseEntity<?> triggerNow(HttpSession session) {
        User user = resolveUser(session);
        if (user == null) return ResponseEntity.status(401).body(Map.of("error", "Not logged in."));
        log.info("[TRIGGER] Manual notification trigger by userId={}", user.getId());
        scheduler.runNow();
        return ResponseEntity.ok(Map.of("message", "Scheduler triggered. Check server logs for results."));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Converts a DailyGoal (or null) to the JSON shape the frontend expects.
     *
     * completedMinutes is always derived from the live session total via
     * DailyGoalService.computeTodayActualMinutes, NOT from the stored counter.
     * This guarantees the goal card and the Study Tracker always show the same number.
     */
    private Map<String, Object> toMap(DailyGoal g, User user) {
        // Always compute from session records — same source as the Study Tracker.
        int actualMinutes = (user != null) ? dailyGoalService.computeTodayActualMinutes(user) : 0;

        // Persistent email saved on the user's account (set via "Set Email" button).
        // This is the value shown in the email input for preloading.
        String userAccountabilityEmail = (user != null && user.getAccountabilityEmail() != null)
                ? user.getAccountabilityEmail() : "";

        Map<String, Object> m = new HashMap<>();
        if (g == null) {
            m.put("goalMinutes",             0);
            m.put("completedMinutes",        actualMinutes);
            m.put("status",                  "none");
            m.put("notificationEnabled",     false);
            m.put("accountabilityEmail",     userAccountabilityEmail);
            m.put("goalReachedEmailSent",    false);
            m.put("emailAlertSent",          false);
            m.put("username",                user != null ? user.getUsername() : "");
            return m;
        }

        int    goal   = g.getGoalMinutes();
        String status = (goal == 0) ? "none" : (actualMinutes >= goal ? "achieved" : "in_progress");

        m.put("goalMinutes",             goal);
        m.put("completedMinutes",        actualMinutes);
        m.put("status",                  status);
        m.put("notificationEnabled",     g.isNotificationEnabled());
        // Always show the user's persistent email in the input (not the per-day snapshot).
        // The per-day DailyGoal.accountabilityEmail is used internally for email sending only.
        m.put("accountabilityEmail",     userAccountabilityEmail);
        m.put("goalReachedEmailSent",    g.isGoalReachedEmailSent());
        m.put("emailAlertSent",          g.isEmailAlertSent());
        m.put("username",                g.getUser().getUsername());
        return m;
    }

    private User resolveUser(HttpSession session) {
        Long id = AuthController.resolveUserId(session);
        if (id == null) return null;
        return userService.findById(id).orElse(null);
    }
}
