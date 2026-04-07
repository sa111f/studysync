package StudySyncer;

import StudySyncer.dto.GoalSaveRequest;
import StudySyncer.entity.DailyGoal;
import StudySyncer.entity.User;
import StudySyncer.repository.DailyGoalRepository;
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

    private final DailyGoalRepository repo;

    public DailyGoalService(DailyGoalRepository repo) {
        this.repo = repo;
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
     * Add completed study minutes to today's record.
     * Creates a bare record (goalMinutes=0) if none exists yet.
     * Only called for truly completed sessions (completed=true).
     */
    @Transactional
    public void addCompletedMinutes(User user, int minutes) {
        if (minutes <= 0) return;
        LocalDate today = LocalDate.now();
        DailyGoal goal = findOrCreate(user, today);
        goal.setCompletedMinutes(goal.getCompletedMinutes() + minutes);
        repo.save(goal);
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
     */
    @Transactional
    public void markNotificationSent(DailyGoal goal, String messageBody) {
        goal.setNotificationSent(true);
        goal.setNotificationSentAt(LocalDateTime.now());
        goal.setNotificationMessage(messageBody);
        repo.save(goal);
        log.info("[NOTIF] Marked notification sent for goalId={} userId={} date={}",
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
