package StudySyncer;

import StudySyncer.dto.GoalSaveRequest;
import StudySyncer.entity.DailyGoal;
import StudySyncer.entity.User;
import StudySyncer.repository.DailyGoalRepository;
import StudySyncer.repository.StudySessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class DailyGoalService {

    private static final Logger log = LoggerFactory.getLogger(DailyGoalService.class);

    /**
     * Accepts digits, spaces, dashes, parentheses, and a leading +.
     * After stripping non-digits the result must be 7–15 characters.
     */
    private static final Pattern PHONE_CHARS = Pattern.compile("^[+\\d\\s().\\-]{7,20}$");

    private final DailyGoalRepository    repo;
    private final SmsService             smsService;
    private final StudySessionRepository sessionRepo;

    public DailyGoalService(DailyGoalRepository repo, SmsService smsService,
                            StudySessionRepository sessionRepo) {
        this.repo        = repo;
        this.smsService  = smsService;
        this.sessionRepo = sessionRepo;
    }

    // ── Session-based progress (single source of truth) ────

    /**
     * Returns the total study minutes for today, summed directly from StudySession
     * records — exactly the same figure the Study Tracker's Today view shows.
     * ALL sessions are counted regardless of their {@code completed} flag.
     */
    public int computeTodayActualMinutes(User user) {
        return (int) sessionRepo.sumDurationByUserAndDate(user, LocalDate.now());
    }

    // ── Phase 2: Goal-minutes ──────────────────────────────

    /**
     * Create or update today's goal minutes for a user.
     * Preserves any already-accumulated completedMinutes.
     */
    @Transactional
    public DailyGoal setGoal(User user, int goalMinutes) {
        LocalDate today = LocalDate.now();
        DailyGoal goal = findOrCreate(user, today);
        goal.setGoalMinutes(goalMinutes);
        return repo.save(goal);
    }

    // ── Phase 3: Completed-minutes ─────────────────────────

    /**
     * Syncs today's {@code completedMinutes} with the real session total and checks
     * whether the daily goal was just crossed (triggering an immediate success SMS if so).
     *
     * <p>Called after every session save — regardless of the session's {@code completed}
     * flag — so that the goal progress always matches the Study Tracker's Today view.
     * Uses {@link #computeTodayActualMinutes} as the single source of truth: a direct
     * DB sum over today's {@code StudySession} records, identical to what the tracker
     * query returns.</p>
     *
     * @param user    the user whose goal should be synced
     * @param minutes the session duration that was just saved (used only as a guard;
     *                the actual counter is recomputed from session records, not incremented)
     */
    @Transactional
    public void addCompletedMinutes(User user, int minutes) {
        if (minutes <= 0) return;
        LocalDate today = LocalDate.now();
        DailyGoal goal = findOrCreate(user, today);

        // Recompute from ALL sessions for today — same query the tracker uses —
        // instead of blindly incrementing a counter that can drift out of sync.
        int actualTotal = computeTodayActualMinutes(user);
        goal.setCompletedMinutes(actualTotal);
        goal = repo.save(goal);

        // Immediate success trigger: fire as soon as the goal threshold is crossed.
        // The notificationSent guard ensures this runs at most once per day.
        checkAndSendSuccessNotification(goal);
    }

    /**
     * Checks whether the user just reached their daily goal and, if all conditions
     * are met, sends the success SMS immediately (not waiting for 23:59).
     *
     * Conditions (all must be true):
     *   - goalMinutes > 0 (a real goal exists)
     *   - completedMinutes >= goalMinutes (threshold just crossed or already past)
     *   - notificationEnabled = true
     *   - consentConfirmed = true
     *   - a valid phone number is stored
     *   - notificationSent = false (idempotency guard — sends at most once)
     *
     * The notificationSent flag is persisted ONLY after Twilio confirms dispatch.
     * If Twilio fails the record stays unsent; the end-of-day scheduler will then
     * send the failure message instead (which is accurate — the SMS was never sent).
     */
    private void checkAndSendSuccessNotification(DailyGoal goal) {
        if (goal.getGoalMinutes() <= 0)                    return;
        if (goal.getCompletedMinutes() < goal.getGoalMinutes()) return;
        if (!goal.isNotificationEnabled())                 return;
        if (!goal.isConsentConfirmed())                    return;
        if (goal.isNotificationSent())                     return;  // already sent today
        String phone = goal.getAccountabilityPhone();
        if (phone == null || phone.isBlank())              return;

        String username = goal.getUser().getUsername();
        String message  = "StudySyncer alert: " + username + " reached today's study goal.";

        log.info("[NOTIF] Immediate success trigger — goalId={} userId={} done={}min goal={}min",
                goal.getId(), goal.getUser().getId(),
                goal.getCompletedMinutes(), goal.getGoalMinutes());

        boolean ok = smsService.send(phone, message);
        if (ok) {
            markNotificationSent(goal, message, "SUCCESS");
        } else {
            log.error("[NOTIF] Immediate success SMS failed for goalId={} userId={} — " +
                            "end-of-day scheduler will send failure if goal remains unmet",
                    goal.getId(), goal.getUser().getId());
        }
    }

    // ── Phase 4: Goal + accountability settings in one call ─

    /**
     * Save goal minutes AND accountability SMS settings from a single UI request.
     * Validates phone number when notifications are enabled.
     * Throws IllegalArgumentException with a user-facing message on bad input.
     */
    @Transactional
    public DailyGoal saveGoalAndSettings(User user, GoalSaveRequest req) {
        if (req.getGoalMinutes() < 1 || req.getGoalMinutes() > 1440) {
            throw new IllegalArgumentException("Goal must be between 1 and 1440 minutes.");
        }

        if (req.isNotificationEnabled()) {
            if (!req.isConsentConfirmed()) {
                throw new IllegalArgumentException(
                        "You must confirm that your contact agreed to receive StudySyncer messages.");
            }
            String phone = normalizePhone(req.getAccountabilityPhone());
            if (phone == null) {
                throw new IllegalArgumentException(
                        "A valid phone number is required when accountability SMS is enabled.");
            }
            req.setAccountabilityPhone(phone);
        }

        LocalDate today = LocalDate.now();
        DailyGoal goal = findOrCreate(user, today);

        goal.setGoalMinutes(req.getGoalMinutes());
        goal.setNotificationEnabled(req.isNotificationEnabled());
        goal.setAccountabilityPhone(
                req.isNotificationEnabled() ? req.getAccountabilityPhone() : null);
        goal.setContactName(
                req.getContactName() != null && !req.getContactName().isBlank()
                        ? req.getContactName().strip() : null);
        goal.setConsentConfirmed(req.isNotificationEnabled() && req.isConsentConfirmed());

        return repo.save(goal);
    }

    // ── Phase 4: Scheduler helpers ─────────────────────────

    /**
     * Returns all DailyGoal records for the given date that are ready to send:
     * notificationEnabled=true, consentConfirmed=true, notificationSent=false.
     */
    public List<DailyGoal> findPendingNotifications(LocalDate date) {
        return repo.findAllByGoalDateAndNotificationEnabledTrueAndConsentConfirmedTrueAndNotificationSentFalse(date);
    }

    /**
     * Marks a DailyGoal as having had its notification sent.
     * Only call this after the SMS provider confirms dispatch.
     *
     * @param goal        the goal record to update
     * @param messageBody exact SMS body that was sent (stored for audit)
     * @param type        "SUCCESS" (goal reached, immediate trigger) or
     *                    "FAILURE" (goal missed, end-of-day scheduler)
     */
    @Transactional
    public void markNotificationSent(DailyGoal goal, String messageBody, String type) {
        goal.setNotificationSent(true);
        goal.setNotificationSentAt(LocalDateTime.now());
        goal.setNotificationMessage(messageBody);
        goal.setNotificationType(type);
        repo.save(goal);
        log.info("[NOTIF] Marked notification sent — goalId={} userId={} date={} type={}",
                goal.getId(), goal.getUser().getId(), goal.getGoalDate(), type);
    }

    /**
     * Returns all DailyGoal records for the given date where the email alert
     * hasn't been sent yet and the user set a real goal (> 0 minutes).
     * The caller (scheduler) decides whether the goal was missed before sending.
     */
    public List<DailyGoal> findPendingEmailAlerts(LocalDate date) {
        return repo.findAllByGoalDateAndEmailAlertSentFalseAndGoalMinutesGreaterThan(date, 0);
    }

    /**
     * Marks a DailyGoal's email alert as sent.
     * Only call this after Resend confirms the email was dispatched.
     */
    @Transactional
    public void markEmailAlertSent(DailyGoal goal) {
        goal.setEmailAlertSent(true);
        goal.setEmailAlertSentAt(LocalDateTime.now());
        repo.save(goal);
        log.info("[EMAIL] Marked email alert sent — goalId={} userId={} date={}",
                goal.getId(), goal.getUser().getId(), goal.getGoalDate());
    }

    // ── Queries ────────────────────────────────────────────

    /** Returns today's DailyGoal for the user, or empty if none exists. */
    public Optional<DailyGoal> getTodayGoal(User user) {
        return repo.findByUserAndGoalDate(user, LocalDate.now());
    }

    // ── Private helpers ────────────────────────────────────

    private DailyGoal findOrCreate(User user, LocalDate date) {
        return repo.findByUserAndGoalDate(user, date).orElseGet(() -> {
            DailyGoal g = new DailyGoal();
            g.setUser(user);
            g.setGoalDate(date);
            return g;
        });
    }

    /**
     * Basic phone normalisation. Strips spaces/dashes/parens.
     * Returns the normalised string, or null if the input is unusable.
     */
    private String normalizePhone(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String trimmed = raw.strip();
        if (!PHONE_CHARS.matcher(trimmed).matches()) return null;
        // Count digit-only length
        String digitsOnly = trimmed.replaceAll("[^\\d]", "");
        if (digitsOnly.length() < 7 || digitsOnly.length() > 15) return null;
        // Preserve leading + for E.164; otherwise return with spaces removed
        return trimmed.startsWith("+")
                ? "+" + trimmed.substring(1).replaceAll("[^\\d]", "")
                : trimmed.replaceAll("[^\\d]", "");
    }
}
