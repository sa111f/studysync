package StudySyncer;

import StudySyncer.dto.GoalSaveRequest;
import StudySyncer.entity.DailyGoal;
import StudySyncer.entity.User;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/daily-goal")
public class DailyGoalController {

    private static final Logger log = LoggerFactory.getLogger(DailyGoalController.class);

    private final DailyGoalService        dailyGoalService;
    private final DailyGoalRolloverService rolloverService;
    private final UserService             userService;
    private final AccountabilityScheduler scheduler;

    public DailyGoalController(DailyGoalService dailyGoalService,
                                DailyGoalRolloverService rolloverService,
                                UserService userService,
                                AccountabilityScheduler scheduler) {
        this.dailyGoalService = dailyGoalService;
        this.rolloverService  = rolloverService;
        this.userService      = userService;
        this.scheduler        = scheduler;
    }

    // ── GET /api/daily-goal/today?tz=America/Toronto ──────────────────────────

    /**
     * Returns current goal, progress, and email accountability settings for today.
     *
     * The optional `tz` query parameter accepts an IANA timezone string from the
     * browser (e.g. "America/Toronto"). When provided:
     *   - The user's stored timezone is updated (if different)
     *   - A rollover check is performed: if midnight passed in the user's timezone
     *     since their last goal day, any missed goal email is sent now
     *
     * This ensures rollover is caught even when the user was offline at midnight.
     */
    @GetMapping("/today")
    public ResponseEntity<?> getToday(
            @RequestParam(required = false) String tz,
            HttpSession session) {

        User user = resolveUser(session);
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of(
                    "error", "Not logged in.",
                    "code",  "UNAUTHORIZED"));
        }

        // Resolve timezone from browser (safe — returns DEFAULT_ZONE on null / invalid input)
        ZoneId zoneId = DailyGoalRolloverService.parseTimezone(tz);

        // Persist timezone — wrapped so a transient DB issue here never breaks the whole load
        if (tz != null && !tz.isBlank()) {
            try {
                userService.updateTimezoneIfChanged(user, tz);
                // Reload so the rollover service sees the fresh timezone value
                user = userService.findById(user.getId()).orElse(user);
            } catch (Exception e) {
                log.warn("[GOAL] Could not update timezone for userId={} tz='{}': {}",
                        user.getId(), tz, e.getMessage(), e);
            }
        }

        // Catch-up rollover: send any missed-goal emails for days that ended while the user
        // was offline or the server was restarting. Wrapped so a rollover failure never
        // prevents the goal card from loading.
        try {
            rolloverService.processRolloverForUser(user, zoneId);
        } catch (Exception e) {
            log.error("[ROLLOVER] Uncaught error in processRolloverForUser for userId={}: {}",
                    user.getId(), e.getMessage(), e);
            // Non-fatal — continue to return goal data
        }

        try {
            Optional<DailyGoal> opt = dailyGoalService.getTodayGoal(user);
            return ResponseEntity.ok(toMap(opt.orElse(null), user));
        } catch (Exception e) {
            log.error("[GOAL] Failed to load today's goal for userId={}: {}", user.getId(), e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Failed to load goal. Please try again.",
                    "code",  "LOAD_FAILED"));
        }
    }

    // ── POST /api/daily-goal ───────────────────────────────────────────────────

    /**
     * Saves today's goal minutes + email accountability settings in one call.
     * Also triggers a rollover check in case midnight passed since the last load.
     *
     * Request body includes an optional `timezone` field (IANA string) so the
     * frontend's detected timezone is persisted alongside the goal.
     */
    @PostMapping
    public ResponseEntity<?> saveGoal(@RequestBody GoalSaveRequest req, HttpSession session) {
        User user = resolveUser(session);
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of(
                    "error", "Not logged in.",
                    "code",  "UNAUTHORIZED"));
        }

        // Persist timezone if provided
        ZoneId zoneId = DailyGoalRolloverService.parseTimezone(null); // default
        String tz = req.getTimezone();
        if (tz != null && !tz.isBlank()) {
            zoneId = DailyGoalRolloverService.parseTimezone(tz);
            try {
                userService.updateTimezoneIfChanged(user, tz);
                user = userService.findById(user.getId()).orElse(user);
            } catch (Exception e) {
                log.warn("[GOAL] Could not update timezone on save for userId={} tz='{}': {}",
                        user.getId(), tz, e.getMessage(), e);
            }
        }

        // Catch-up rollover before saving settings so past days are evaluated first
        try {
            rolloverService.processRolloverForUser(user, zoneId);
        } catch (Exception e) {
            log.error("[ROLLOVER] Uncaught error during goal save for userId={}: {}",
                    user.getId(), e.getMessage(), e);
        }

        try {
            DailyGoal goal = dailyGoalService.saveGoalAndSettings(user, req);
            log.info("[GOAL] Saved — userId={} goalMinutes={} emailEnabled={}",
                    user.getId(), goal.getGoalMinutes(), goal.isNotificationEnabled());
            return ResponseEntity.ok(toMap(goal, user));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", e.getMessage(),
                    "code",  "VALIDATION_ERROR"));
        } catch (Exception e) {
            log.error("[GOAL] Unexpected error saving goal for userId={} goalMinutes={}: {}",
                    user.getId(), req.getGoalMinutes(), e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Failed to save goal. Please try again.",
                    "code",  "SAVE_FAILED"));
        }
    }

    // ── POST /api/daily-goal/trigger-notifications (testing only) ─────────────

    /**
     * Manually triggers both the rollover check and the daily scheduler right now.
     * Useful during development/testing so you don't have to wait for scheduled times.
     */
    @PostMapping("/trigger-notifications")
    public ResponseEntity<?> triggerNow(HttpSession session) {
        User user = resolveUser(session);
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of(
                    "error", "Not logged in.",
                    "code",  "UNAUTHORIZED"));
        }
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
     *
     * Also includes the user's stored timezone so the frontend can display it.
     */
    private Map<String, Object> toMap(DailyGoal g, User user) {
        int actualMinutes = (user != null) ? dailyGoalService.computeTodayActualMinutes(user) : 0;

        String userAccountabilityEmail = (user != null && user.getAccountabilityEmail() != null)
                ? user.getAccountabilityEmail() : "";

        // Timezone: stored on user, or fallback to Toronto
        String timezone = (user != null && user.getTimezone() != null && !user.getTimezone().isBlank())
                ? user.getTimezone() : "America/Toronto";

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
            m.put("timezone",                timezone);
            return m;
        }

        int    goal   = g.getGoalMinutes();
        String status = (goal == 0) ? "none" : (actualMinutes >= goal ? "achieved" : "in_progress");

        m.put("goalMinutes",             goal);
        m.put("completedMinutes",        actualMinutes);
        m.put("status",                  status);
        m.put("notificationEnabled",     g.isNotificationEnabled());
        m.put("accountabilityEmail",     userAccountabilityEmail);
        m.put("goalReachedEmailSent",    g.isGoalReachedEmailSent());
        m.put("emailAlertSent",          g.isEmailAlertSent());
        m.put("username",                g.getUser().getUsername());
        m.put("timezone",                timezone);
        return m;
    }

    private User resolveUser(HttpSession session) {
        Long id = AuthController.resolveUserId(session);
        if (id == null) return null;
        return userService.findById(id).orElse(null);
    }
}
