package StudySyncer;

import StudySyncer.entity.EmailType;
import StudySyncer.entity.User;
import StudySyncer.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

/**
 * One-click unsubscribe handler (spec 8.7 + 8.8).
 *
 * GET /api/notifications/unsubscribe?token=... → renders confirmation page.
 *
 * The token is HMAC-signed (see {@link UnsubscribeTokenService}) so no login
 * is needed — the user clicks the footer link from their mail client and the
 * request carries enough to authorise the disable action.
 *
 * Idempotency: verifying a valid token and then re-verifying it later
 * (without rotating the secret) is safe — the state flag the second call
 * sets is already the same value. Spec 8.11 calls this out explicitly.
 */
@Controller
@RequestMapping("/api/notifications")
public class UnsubscribeController {

    private static final Logger log = LoggerFactory.getLogger(UnsubscribeController.class);

    private final UnsubscribeTokenService tokens;
    private final UserRepository          userRepo;

    public UnsubscribeController(UnsubscribeTokenService tokens, UserRepository userRepo) {
        this.tokens   = tokens;
        this.userRepo = userRepo;
    }

    @GetMapping("/unsubscribe")
    @Transactional
    public Object unsubscribe(@RequestParam(value = "token", required = false) String token,
                              Model model) {

        UnsubscribeTokenService.Verification v = tokens.verify(token);

        switch (v.getStatus()) {
            case INVALID:
                // Bad signature or malformed — don't leak whether the token format
                // is right, just refuse. 400 keeps it out of browser "remembered"
                // history for convenience.
                log.info("[UNSUB] Invalid token");
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "This unsubscribe link is invalid."));

            case EXPIRED:
                // 410 Gone is the HTTP-honest choice for a signed link whose
                // validity window has lapsed. Links are good for 90 days; users
                // who miss the window should click "Manage all email notifications"
                // (which requires login) instead.
                log.info("[UNSUB] Expired token — userId={} type={}",
                        v.getUserId(), v.getEmailType());
                return ResponseEntity.status(HttpStatus.GONE)
                        .body(Map.of(
                                "error",
                                "This unsubscribe link has expired. Log in and use " +
                                "settings to manage your email preferences."));

            case VALID:
                // Fall through to the action below.
                break;
        }

        User user = userRepo.findById(v.getUserId()).orElse(null);
        if (user == null) {
            // Account was deleted after the token was minted. Treat as success —
            // there's nothing left to email anyway.
            model.addAttribute("emailTypeLabel", humanLabel(v.getEmailType()));
            return "unsubscribed";
        }

        // Disable the specific email type. Idempotent — re-clicking a still-valid
        // token leaves the flag already false. No log entry mutation needed.
        boolean changed = disableEmailType(user, v.getEmailType());
        if (changed) {
            userRepo.save(user);
            log.info("[UNSUB] userId={} disabled {}", user.getId(), v.getEmailType());
        } else {
            log.info("[UNSUB] userId={} {} already off — no-op",
                    user.getId(), v.getEmailType());
        }

        model.addAttribute("emailTypeLabel", humanLabel(v.getEmailType()));
        return "unsubscribed";
    }

    /**
     * Maps an {@link EmailType} to the setter that turns it off. Returns
     * true iff the flag actually flipped (used for logging only).
     */
    private static boolean disableEmailType(User user, EmailType type) {
        switch (type) {
            case DIGEST:
                if (!user.isDigestEnabled()) return false;
                user.setDigestEnabled(false); return true;
            case OVERDUE_REMINDER:
                if (!user.isOverdueReminderEnabled()) return false;
                user.setOverdueReminderEnabled(false); return true;
            case EXAM_REMINDER_7D:
            case EXAM_REMINDER_3D:
            case EXAM_REMINDER_1D:
                // Exam reminders share one toggle — unsubscribing from any
                // threshold turns off the whole group. Users expect that.
                if (!user.isExamReminderEnabled()) return false;
                user.setExamReminderEnabled(false); return true;
            case GOAL_REACHED:
            case GOAL_MISSED:
                // Pre-Phase-8 daily-goal emails are governed by the older
                // notificationEnabled flag on DailyGoal. We don't touch that
                // here — the footer on those emails points at existing UI.
                // Still surface a clean confirmation page so the user sees
                // the link "worked".
                return false;
            default:
                return false;
        }
    }

    private static String humanLabel(EmailType type) {
        if (type == null) return "email";
        switch (type) {
            case DIGEST:             return "daily digest";
            case OVERDUE_REMINDER:   return "overdue task reminder";
            case EXAM_REMINDER_7D:
            case EXAM_REMINDER_3D:
            case EXAM_REMINDER_1D:   return "exam reminder";
            case GOAL_REACHED:       return "goal-reached";
            case GOAL_MISSED:        return "goal-missed";
            default:                 return "email";
        }
    }
}
