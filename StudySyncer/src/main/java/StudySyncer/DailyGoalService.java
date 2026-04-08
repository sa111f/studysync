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

@Service
public class DailyGoalService {

    private static final Logger log = LoggerFactory.getLogger(DailyGoalService.class);

    private final DailyGoalRepository    repo;
    private final StudySessionRepository sessionRepo;
    private final EmailService           emailService;

    public DailyGoalService(DailyGoalRepository repo,
                            StudySessionRepository sessionRepo,
                            EmailService emailService) {
        this.repo         = repo;
        this.sessionRepo  = sessionRepo;
        this.emailService = emailService;
    }

    // ── Session-based progress (single source of truth) ────────────────────────

    /**
     * Returns the total study minutes for today, summed directly from StudySession
     * records — the same figure the Study Tracker's Today view shows.
     */
    public int computeTodayActualMinutes(User user) {
        return (int) sessionRepo.sumDurationByUserAndDate(user, LocalDate.now());
    }

    // ── Goal minutes ────────────────────────────────────────────────────────────

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

    // ── Completed minutes ───────────────────────────────────────────────────────

    /**
     * Syncs today's completedMinutes with the real session total, then checks
     * whether the user just crossed their daily goal threshold and — if so —
     * fires an immediate goal-reached email.
     *
     * Called after every session save so goal progress always matches the tracker.
     *
     * @param user    the user whose goal should be synced
     * @param minutes the duration of the session just saved (used as a guard; the
     *                counter is recomputed from DB records, not blindly incremented)
     */
    @Transactional
    public void addCompletedMinutes(User user, int minutes) {
        if (minutes <= 0) return;

        LocalDate today = LocalDate.now();
        DailyGoal goal = findOrCreate(user, today);

        // Recompute from ALL sessions for today — same query the tracker uses.
        int actualTotal = computeTodayActualMinutes(user);
        goal.setCompletedMinutes(actualTotal);
        goal = repo.save(goal);

        // Immediate success trigger: fire as soon as the goal threshold is crossed.
        // The goalReachedEmailSent flag ensures this runs at most once per day.
        checkAndSendGoalReachedEmail(goal);
    }

    // ── Goal + email accountability settings in one call ────────────────────────

    /**
     * Saves goal minutes AND email accountability settings from a single UI request.
     * Validates that an email address is available when accountability is enabled.
     *
     * Throws IllegalArgumentException with a user-facing message on bad input.
     */
    @Transactional
    public DailyGoal saveGoalAndSettings(User user, GoalSaveRequest req) {
        if (req.getGoalMinutes() < 1 || req.getGoalMinutes() > 1440) {
            throw new IllegalArgumentException("Goal must be between 1 and 1440 minutes.");
        }

        // When email accountability is enabled, verify there is somewhere to send the alert.
        if (req.isNotificationEnabled()) {
            String enteredEmail  = req.getAccountabilityEmail();
            boolean hasEntered   = enteredEmail != null && !enteredEmail.isBlank();
            boolean hasRegistered = user.getEmail() != null && !user.getEmail().isBlank();

            if (!hasEntered && !hasRegistered) {
                throw new IllegalArgumentException(
                        "Please enter an email address, or register with an email to use accountability emails.");
            }
            if (hasEntered && !enteredEmail.contains("@")) {
                throw new IllegalArgumentException("Please enter a valid email address.");
            }
        }

        LocalDate today = LocalDate.now();
        DailyGoal goal = findOrCreate(user, today);

        goal.setGoalMinutes(req.getGoalMinutes());
        goal.setNotificationEnabled(req.isNotificationEnabled());

        // Store accountability email only when accountability is enabled
        String emailToStore = null;
        if (req.isNotificationEnabled()) {
            String entered = req.getAccountabilityEmail();
            emailToStore = (entered != null && !entered.isBlank()) ? entered.strip() : null;
        }
        goal.setAccountabilityEmail(emailToStore);

        return repo.save(goal);
    }

    // ── Scheduler helpers ───────────────────────────────────────────────────────

    /**
     * Returns all DailyGoal records for the given date that need a missed-goal email.
     * Only returns records where:
     *   - accountability is enabled
     *   - missed-goal email not yet sent
     *   - goal-reached email not yet sent (goal was not reached → may need missed-goal email)
     *   - a real goal was set (> 0 min)
     */
    public List<DailyGoal> findPendingEmailAlerts(LocalDate date) {
        return repo
            .findAllByGoalDateAndNotificationEnabledTrueAndEmailAlertSentFalseAndGoalReachedEmailSentFalseAndGoalMinutesGreaterThan(
                date, 0);
    }

    /**
     * Marks a DailyGoal's goal-reached email as sent.
     * Only call this after Resend confirms the email was dispatched.
     */
    @Transactional
    public void markGoalReachedEmailSent(DailyGoal goal) {
        goal.setGoalReachedEmailSent(true);
        goal.setGoalReachedEmailSentAt(LocalDateTime.now());
        repo.save(goal);
        log.info("[EMAIL] Marked goal-reached email sent — goalId={} userId={} date={}",
                goal.getId(), goal.getUser().getId(), goal.getGoalDate());
    }

    /**
     * Marks a DailyGoal's missed-goal email as sent.
     * Only call this after Resend confirms the email was dispatched.
     */
    @Transactional
    public void markEmailAlertSent(DailyGoal goal) {
        goal.setEmailAlertSent(true);
        goal.setEmailAlertSentAt(LocalDateTime.now());
        repo.save(goal);
        log.info("[EMAIL] Marked missed-goal email sent — goalId={} userId={} date={}",
                goal.getId(), goal.getUser().getId(), goal.getGoalDate());
    }

    // ── Queries ─────────────────────────────────────────────────────────────────

    /** Returns today's DailyGoal for the user, or empty if none exists. */
    public Optional<DailyGoal> getTodayGoal(User user) {
        return repo.findByUserAndGoalDate(user, LocalDate.now());
    }

    // ── Private helpers ──────────────────────────────────────────────────────────

    /**
     * Checks whether the user just reached their daily goal and, if all conditions
     * are met, sends the goal-reached email immediately.
     *
     * Conditions (all must be true):
     *   - goalMinutes > 0 (a real goal is set)
     *   - completedMinutes >= goalMinutes (threshold just crossed)
     *   - notificationEnabled = true (user opted in)
     *   - goalReachedEmailSent = false (idempotency — send at most once per day)
     *
     * Email failure is caught internally — it must never crash the session save flow.
     */
    private void checkAndSendGoalReachedEmail(DailyGoal goal) {
        // Guard: no goal set, or threshold not yet reached
        if (goal.getGoalMinutes() <= 0)                                  return;
        if (goal.getCompletedMinutes() < goal.getGoalMinutes())          return;
        // Guard: user didn't opt in to email accountability
        if (!goal.isNotificationEnabled())                               return;
        // Guard: already sent today — idempotency
        if (goal.isGoalReachedEmailSent())                               return;

        String userName = goal.getUser().getUsername();

        // Resolve recipient: prefer the accountability email on the goal record,
        // then fall back to the user's registered email.
        // EmailService handles the final fallback to ALERT_TO_EMAIL.
        String recipient = goal.getAccountabilityEmail();
        if (recipient == null || recipient.isBlank()) {
            recipient = goal.getUser().getEmail();
        }

        log.info("[EMAIL] Immediate goal-reached trigger — goalId={} userId={} done={}min goal={}min",
                goal.getId(), goal.getUser().getId(),
                goal.getCompletedMinutes(), goal.getGoalMinutes());

        // Wrap in try/catch so a failure here never breaks the timer or session save
        try {
            boolean ok = emailService.sendGoalReachedEmail(
                    recipient, userName, goal.getGoalMinutes(),
                    goal.getCompletedMinutes(), goal.getGoalDate());

            if (ok) {
                markGoalReachedEmailSent(goal);
            } else {
                log.warn("[EMAIL] Goal-reached email failed for goalId={} userId={} — " +
                        "will not retry (missed-goal email will also be suppressed at end of day)",
                        goal.getId(), goal.getUser().getId());
            }
        } catch (Exception e) {
            // Safety net: should never reach here since EmailService catches its own errors,
            // but we guard here too so the session-save transaction is never affected.
            log.error("[EMAIL] Unexpected error in goal-reached trigger for goalId={}: {}",
                    goal.getId(), e.getMessage(), e);
        }
    }

    private DailyGoal findOrCreate(User user, LocalDate date) {
        return repo.findByUserAndGoalDate(user, date).orElseGet(() -> {
            DailyGoal g = new DailyGoal();
            g.setUser(user);
            g.setGoalDate(date);
            return g;
        });
    }
}
