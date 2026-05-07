package StudySyncer;

import StudySyncer.dto.NotificationPreferencesDto;
import StudySyncer.entity.User;
import StudySyncer.repository.UserRepository;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * User-facing notification preferences (Phase 8.7).
 *
 * GET  /api/notifications/preferences → current toggle + time settings
 * PUT  /api/notifications/preferences → update, with UX-sanity validation
 *
 * The unsubscribe footer link points to {@link UnsubscribeController},
 * not here, because unsubscribing doesn't require a logged-in session.
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationPreferencesController {

    private static final Logger log = LoggerFactory.getLogger(NotificationPreferencesController.class);

    private final UserService    userService;
    private final UserRepository userRepository;

    public NotificationPreferencesController(UserService userService, UserRepository userRepository) {
        this.userService    = userService;
        this.userRepository = userRepository;
    }

    @GetMapping("/preferences")
    public ResponseEntity<?> get(HttpSession session) {
        User user = resolveUser(session);
        if (user == null) return unauthorized();
        return ResponseEntity.ok(NotificationPreferencesDto.from(user));
    }

    @PutMapping("/preferences")
    @Transactional
    public ResponseEntity<?> update(@RequestBody NotificationPreferencesDto req, HttpSession session) {
        User user = resolveUser(session);
        if (user == null) return unauthorized();

        LocalTime digestTime;
        LocalTime overdueTime;
        try {
            digestTime  = parseLocalTime(req.getDigestLocalTime(),          LocalTime.of(8, 0));
            overdueTime = parseLocalTime(req.getOverdueReminderLocalTime(), LocalTime.of(20, 0));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid time — use HH:mm (e.g. 08:00)."));
        }

        // UX sanity: if both digest and overdue are enabled, they must not
        // fire at the same minute. Spec 8.7 flags this with 400 — keeps
        // the two emails from arriving on top of each other.
        if (req.isDigestEnabled() && req.isOverdueReminderEnabled()
                && digestTime.equals(overdueTime)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error",
                            "Digest and overdue reminders can't be at the exact same time."));
        }

        user.setDigestEnabled(req.isDigestEnabled());
        user.setDigestLocalTime(digestTime);
        user.setOverdueReminderEnabled(req.isOverdueReminderEnabled());
        user.setOverdueReminderLocalTime(overdueTime);
        user.setExamReminderEnabled(req.isExamReminderEnabled());
        userRepository.save(user);

        log.info("[NOTIF] userId={} prefs updated digest={}@{} overdue={}@{} exam={}",
                user.getId(),
                req.isDigestEnabled(), digestTime,
                req.isOverdueReminderEnabled(), overdueTime,
                req.isExamReminderEnabled());

        return ResponseEntity.ok(NotificationPreferencesDto.from(user));
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private static LocalTime parseLocalTime(String s, LocalTime fallback) {
        if (s == null || s.isBlank()) return fallback;
        // Accept "HH:mm" and "HH:mm:ss" (Java's default LocalTime.toString).
        String t = s.trim();
        return t.length() == 5
                ? LocalTime.parse(t, DateTimeFormatter.ofPattern("HH:mm"))
                : LocalTime.parse(t);
    }

    private User resolveUser(HttpSession session) {
        Long id = AuthController.resolveUserId(session);
        if (id == null) return null;
        return userService.findById(id).orElse(null);
    }

    private ResponseEntity<?> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "Not logged in."));
    }
}
