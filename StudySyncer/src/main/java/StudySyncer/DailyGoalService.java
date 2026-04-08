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

    public DailyGoalService(DailyGoalRepository repo, StudySessionRepository sessionRepo) {
        this.repo        = repo;
        this.sessionRepo = sessionRepo;
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
     * Syncs today's completedMinutes with the real session total.
     * Called after every session save so goal progress always matches the tracker.
     *
     * @param user    the user whose goal should be synced
     * @param minutes the session duration just saved (used as a guard; actual
     *                counter is recomputed from DB, not incremented)
     */
    @Transactional
    public void addCompletedMinutes(User user, int minutes) {
        if (minutes <= 0) return;
        LocalDate today = LocalDate.now();
        DailyGoal goal = findOrCreate(user, today);

        // Recompute from ALL sessions for today — same query the tracker uses.
        int actualTotal = computeTodayActualMinutes(user);
        goal.setCompletedMinutes(actualTotal);
        repo.save(goal);
    }

    // ── Goal + email accountability settings in one call ────────────────────────

    /**
     * Saves goal minutes AND email accountability settings from a single UI request.
     * Validates that an email address is provided (or the user has one registered)
     * when accountability is enabled.
     *
     * Throws IllegalArgumentException with a user-facing message on bad input.
     */
    @Transactional
    public DailyGoal saveGoalAndSettings(User user, GoalSaveRequest req) {
        if (req.getGoalMinutes() < 1 || req.getGoalMinutes() > 1440) {
            throw new IllegalArgumentException("Goal must be between 1 and 1440 minutes.");
        }

        // When email accountability is enabled, verify there's somewhere to send the alert.
        if (req.isNotificationEnabled()) {
            String enteredEmail = req.getAccountabilityEmail();
            boolean hasEnteredEmail  = enteredEmail != null && !enteredEmail.isBlank();
            boolean hasRegisteredEmail = user.getEmail() != null && !user.getEmail().isBlank();

            if (!hasEnteredEmail && !hasRegisteredEmail) {
                throw new IllegalArgumentException(
                        "Please enter an email address, or register with an email to use accountability emails.");
            }

            // Basic email format check when one was entered
            if (hasEnteredEmail && !enteredEmail.contains("@")) {
                throw new IllegalArgumentException("Please enter a valid email address.");
            }
        }

        LocalDate today = LocalDate.now();
        DailyGoal goal = findOrCreate(user, today);

        goal.setGoalMinutes(req.getGoalMinutes());
        goal.setNotificationEnabled(req.isNotificationEnabled());

        // Store the accountability email only when accountability is enabled
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
     * Returns all DailyGoal records for the given date where:
     *   - the user enabled email accountability (notificationEnabled=true)
     *   - the email alert hasn't been sent yet
     *   - the user actually set a real goal (> 0 min)
     *
     * The scheduler checks whether the goal was missed before sending.
     */
    public List<DailyGoal> findPendingEmailAlerts(LocalDate date) {
        return repo.findAllByGoalDateAndNotificationEnabledTrueAndEmailAlertSentFalseAndGoalMinutesGreaterThan(date, 0);
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

    // ── Queries ─────────────────────────────────────────────────────────────────

    /** Returns today's DailyGoal for the user, or empty if none exists. */
    public Optional<DailyGoal> getTodayGoal(User user) {
        return repo.findByUserAndGoalDate(user, LocalDate.now());
    }

    // ── Private helpers ──────────────────────────────────────────────────────────

    private DailyGoal findOrCreate(User user, LocalDate date) {
        return repo.findByUserAndGoalDate(user, date).orElseGet(() -> {
            DailyGoal g = new DailyGoal();
            g.setUser(user);
            g.setGoalDate(date);
            return g;
        });
    }
}
