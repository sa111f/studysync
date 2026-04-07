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

    // ── GET /api/daily-goal/today ──────────────────────────

    /** Returns current goal, progress, and accountability settings for today. */
    @GetMapping("/today")
    public ResponseEntity<?> getToday(HttpSession session) {
        User user = resolveUser(session);
        if (user == null) return ResponseEntity.status(401).body(Map.of("error", "Not logged in."));
        Optional<DailyGoal> opt = dailyGoalService.getTodayGoal(user);
        return ResponseEntity.ok(toMap(opt.orElse(null), user));
    }

    // ── POST /api/daily-goal ───────────────────────────────

    /**
     * Save today's goal minutes + accountability SMS settings in one call.
     * Replaces the old simple {goalMinutes} endpoint with a richer request body.
     */
    @PostMapping
    public ResponseEntity<?> saveGoal(@RequestBody GoalSaveRequest req, HttpSession session) {
        User user = resolveUser(session);
        if (user == null) return ResponseEntity.status(401).body(Map.of("error", "Not logged in."));
        try {
            DailyGoal goal = dailyGoalService.saveGoalAndSettings(user, req);
            log.info("[GOAL] Saved — userId={} goalMinutes={} smsEnabled={}",
                    user.getId(), goal.getGoalMinutes(), goal.isNotificationEnabled());
            return ResponseEntity.ok(toMap(goal, user));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Helpers ───────────────────────────────────────────

    /**
     * Converts a DailyGoal (or null) to the JSON shape the frontend expects.
     * Phase 4 adds notificationEnabled, notificationSent, notificationStatus.
     * username is included for the client-side SMS preview feature.
     */
    private Map<String, Object> toMap(DailyGoal g, User user) {
        Map<String, Object> m = new HashMap<>();
        if (g == null) {
            m.put("goalMinutes",          0);
            m.put("completedMinutes",     0);
            m.put("status",               "none");
            m.put("notificationEnabled",  false);
            m.put("notificationSent",     false);
            m.put("notificationType",     "");
            m.put("notificationStatus",   "disabled");
            m.put("accountabilityPhone",  "");
            m.put("contactName",          "");
            m.put("consentConfirmed",     false);
            m.put("username",             user != null ? user.getUsername() : "");
            return m;
        }

        int    goal   = g.getGoalMinutes();
        int    done   = g.getCompletedMinutes();
        String status = (goal == 0) ? "none" : (done >= goal ? "achieved" : "in_progress");

        // notificationStatus values consumed by the frontend badge:
        //   "disabled"      — SMS not enabled
        //   "pending"       — enabled, not yet sent (goal not yet reached, end-of-day not here)
        //   "success_sent"  — success SMS already fired immediately (goal was reached)
        //   "failure_sent"  — failure SMS sent by end-of-day scheduler (goal was missed)
        String notifStatus;
        if (!g.isNotificationEnabled()) {
            notifStatus = "disabled";
        } else if (g.isNotificationSent()) {
            notifStatus = "SUCCESS".equals(g.getNotificationType()) ? "success_sent" : "failure_sent";
        } else {
            notifStatus = "pending";
        }

        m.put("goalMinutes",          goal);
        m.put("completedMinutes",     done);
        m.put("status",               status);
        m.put("notificationEnabled",  g.isNotificationEnabled());
        m.put("notificationSent",     g.isNotificationSent());
        m.put("notificationType",     g.getNotificationType() != null ? g.getNotificationType() : "");
        m.put("notificationStatus",   notifStatus);
        m.put("accountabilityPhone",  g.getAccountabilityPhone()  != null ? g.getAccountabilityPhone()  : "");
        m.put("contactName",          g.getContactName()          != null ? g.getContactName()          : "");
        m.put("consentConfirmed",     g.isConsentConfirmed());
        m.put("username",             g.getUser().getUsername());
        return m;
    }

    // ── POST /api/daily-goal/trigger-notifications (testing only) ─────────────

    /**
     * Manually triggers the end-of-day scheduler right now.
     * Useful during development/testing so you don't have to wait until 23:59.
     * Remove or guard this endpoint in a production-hardened release.
     */
    @PostMapping("/trigger-notifications")
    public ResponseEntity<?> triggerNow(HttpSession session) {
        User user = resolveUser(session);
        if (user == null) return ResponseEntity.status(401).body(Map.of("error", "Not logged in."));
        log.info("[TRIGGER] Manual notification trigger by userId={}", user.getId());
        scheduler.runNow();
        return ResponseEntity.ok(Map.of("message", "Scheduler triggered. Check server logs for results."));
    }

    // ── Helpers ───────────────────────────────────────────

    private User resolveUser(HttpSession session) {
        Long id = AuthController.resolveUserId(session);
        if (id == null) return null;
        return userService.findById(id).orElse(null);
    }
}
