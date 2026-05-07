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
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

@Service
public class DailyGoalService {

    private static final Logger log = LoggerFactory.getLogger(DailyGoalService.class);

    /**
     * All "today" comparisons use Toronto time, matching the zone used when
     * sessions are saved in TrackerService. This ensures that a user studying
     * at 11 PM Toronto time has their session counted on the correct Toronto
     * calendar day — not bumped to the next UTC day.
     */
    private static final ZoneId TORONTO = ZoneId.of("America/Toronto");

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

    // ── Session-based progress (single source of truth) ─────────────────────────

    /**
     * Returns the total study minutes for today, summed directly from StudySession
     * records — the same figure the Study Tracker's Today view shows.
     *
     * Uses Toronto date so "today" means the current calendar day in Toronto.
     */
    public int computeTodayActualMinutes(User user) {
        return (int) sessionRepo.sumDurationByUserAndDate(user, LocalDate.now(TORONTO));
    }

    // ── Goal minutes ─────────────────────────────────────────────────────────────

    /**
     * Create or update today's goal minutes for a user.
     * Preserves any already-accumulated completedMinutes.
     */
    @Transactional
    public DailyGoal setGoal(User user, int goalMinutes) {
        LocalDate today = LocalDate.now(TORONTO);
        DailyGoal goal = findOrCreate(user, today);
        goal.setGoalMinutes(goalMinutes);
        return repo.save(goal);
    }

    // ── Completed minutes ────────────────────────────────────────────────────────

    /**
     * Syncs today's completedMinutes with the real session total and returns the
     * updated DailyGoal so the caller can check goal-threshold crossing.
     *
     * IMPORTANT: this method is @Transactional and commits before returning.
     * The caller (TrackerService) should check for the goal-reached email AFTER
     * this method returns, so the email fires outside the DB transaction.
     *
     * @param user    the user whose goal should be synced
     * @param minutes the duration of the session just saved (used as a guard only;
     *                the actual counter is recomputed from DB records)
     * @return the updated DailyGoal, or null if minutes <= 0
     */
    @Transactional
    public DailyGoal addCompletedMinutes(User user, int minutes) {
        if (minutes <= 0) return null;

        LocalDate today = LocalDate.now(TORONTO);
        DailyGoal goal = findOrCreate(user, today);

        // Capture the previous count so the caller can detect the threshold crossing
        int previousMinutes = goal.getCompletedMinutes();

        // Recompute from ALL sessions for today — same query the tracker uses.
        int actualTotal = computeTodayActualMinutes(user);
        goal.setCompletedMinutes(actualTotal);
        goal = repo.save(goal);

        log.debug("[GOAL] Progress synced — userId={} goalId={} prev={}min now={}min goal={}min enabled={}",
                user.getId(), goal.getId(), previousMinutes, actualTotal,
                goal.getGoalMinutes(), goal.isNotificationEnabled());

        // Return the saved entity — the CALLER will trigger the email AFTER this
        // transaction commits, so the email fires outside the DB transaction.
        return goal;
    }

    // ── Immediate goal-reached email trigger ─────────────────────────────────────

    /**
     * Checks whether the user's goal was just reached and — if all conditions are
     * met — sends the goal-reached email immediately.
     *
     * MUST be called OUTSIDE any @Transactional context (i.e., after
     * addCompletedMinutes has returned and committed). This ensures:
     *   - The DB update is committed before the email is sent.
     *   - The HTTP call to Resend does not hold a DB connection open.
     *   - If the email fails, the DB update is not rolled back.
     *
     * Conditions checked:
     *   - goalMinutes > 0 (a real goal is set)
     *   - completedMinutes >= goalMinutes (threshold is crossed)
     *   - notificationEnabled = true (user opted in)
     *   - goalReachedEmailSent = false (idempotency — send at most once per day)
     *   - an accountability email is set (goal-level or user-level) — never falls back to login email
     *
     * After a successful send, marks goalReachedEmailSent=true in its own small
     * transaction (via repo.save, which has its own @Transactional).
     */
    public void triggerGoalReachedEmailIfNeeded(DailyGoal goal) {
        if (goal == null) return;

        int goalMin = goal.getGoalMinutes();
        int doneMin = goal.getCompletedMinutes();

        // Guard: no real goal set
        if (goalMin <= 0) {
            log.debug("[EMAIL] Goal-reached check skipped — no goal set (goalId={})", goal.getId());
            return;
        }
        // Guard: threshold not yet reached
        if (doneMin < goalMin) {
            log.debug("[EMAIL] Goal-reached check skipped — not yet reached (done={}min goal={}min goalId={})",
                    doneMin, goalMin, goal.getId());
            return;
        }
        // Guard: user did not opt in to email accountability
        if (!goal.isNotificationEnabled()) {
            log.debug("[EMAIL] Goal-reached check skipped — notifications not enabled (goalId={})", goal.getId());
            return;
        }
        // Guard: idempotency — already sent today
        if (goal.isGoalReachedEmailSent()) {
            log.debug("[EMAIL] Goal-reached check skipped — already sent today (goalId={})", goal.getId());
            return;
        }

        String userName = goal.getUser().getUsername();

        // Resolve recipient:
        //   1. Per-day accountability email on the goal (snapshot saved when goal was set)
        //   2. User's persistent accountability email (set via "Set Email" button)
        //   Never fall back to the user's login email — accountability emails must only
        //   go to an explicitly set accountability address.
        String loginEmail          = goal.getUser().getEmail();
        String persistentAcctEmail = goal.getUser().getAccountabilityEmail();
        String recipient           = goal.getAccountabilityEmail();
        if (recipient == null || recipient.isBlank()) {
            recipient = persistentAcctEmail;
        }

        log.info("[EMAIL] Goal-reached recipient resolution — goalId={} userId={} " +
                        "loginEmail={} accountabilityEmail={} finalRecipient={}",
                goal.getId(), goal.getUser().getId(),
                loginEmail != null ? loginEmail : "(none)",
                persistentAcctEmail != null ? persistentAcctEmail : "(not set)",
                recipient != null ? recipient : "(none — will skip)");

        if (recipient == null || recipient.isBlank()) {
            log.warn("[EMAIL] Goal-reached email skipped — no accountability email set " +
                            "for goalId={} userId={}",
                    goal.getId(), goal.getUser().getId());
            return;
        }

        try {
            boolean ok = emailService.sendGoalReachedEmail(
                    recipient, userName, goalMin, doneMin, goal.getGoalDate());

            if (ok) {
                // Save in its own transaction — repo.save() has @Transactional via Spring Data proxy.
                goal.setGoalReachedEmailSent(true);
                goal.setGoalReachedEmailSentAt(LocalDateTime.now(TORONTO));
                repo.save(goal);
                // Phase 8 retrofit — unified send-log row for auditing + cap.
                emailService.recordGoalEmailSent(
                        goal.getUser(), StudySyncer.entity.EmailType.GOAL_REACHED, goal.getGoalDate());
                log.info("[EMAIL] Goal-reached email sent and recorded — goalId={} userId={}",
                        goal.getId(), goal.getUser().getId());
            } else {
                // Email was skipped or failed — logged inside EmailService already.
                // Do NOT mark as sent so we can see it was attempted.
                log.warn("[EMAIL] Goal-reached email NOT sent for goalId={} — see previous log for reason",
                        goal.getId());
            }
        } catch (Exception e) {
            // Safety net — EmailService already catches its own errors, but guard here too
            // so nothing in this flow can ever surface as an error to the user.
            log.error("[EMAIL] Unexpected error in goal-reached trigger for goalId={}: {}",
                    goal.getId(), e.getMessage(), e);
        }
    }

    // ── Goal + email accountability settings in one call ─────────────────────────

    /**
     * Saves goal minutes AND email accountability settings from a single UI request.
     * Validates that an email address is available when accountability is enabled.
     * Throws IllegalArgumentException with a user-facing message on bad input.
     */
    @Transactional
    public DailyGoal saveGoalAndSettings(User user, GoalSaveRequest req) {
        if (req.getGoalMinutes() < 1 || req.getGoalMinutes() > 1440) {
            throw new IllegalArgumentException("Goal must be between 1 and 1440 minutes.");
        }

        if (req.isNotificationEnabled()) {
            String enteredEmail      = req.getAccountabilityEmail();
            boolean hasEntered       = enteredEmail != null && !enteredEmail.isBlank();
            boolean hasPersistentEmail = user.getAccountabilityEmail() != null
                                      && !user.getAccountabilityEmail().isBlank();

            if (!hasEntered && !hasPersistentEmail) {
                throw new IllegalArgumentException(
                        "Please enter an accountability email address, or use the \"Set Email\" button to save one first.");
            }
            if (hasEntered && !enteredEmail.contains("@")) {
                throw new IllegalArgumentException("Please enter a valid email address.");
            }
        }

        LocalDate today = LocalDate.now(TORONTO);
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

    // ── Scheduler helpers ────────────────────────────────────────────────────────

    /**
     * Returns all DailyGoal records for the given date that need a missed-goal email.
     * Only returns records where:
     *   - accountability is enabled (notificationEnabled = true)
     *   - goal-reached email NOT already sent (goalReachedEmailSent = false)
     *     — if goal was reached, the success email was already sent; no missed-goal needed
     *   - missed-goal email not yet sent (emailAlertSent = false)
     *   - a real goal was set (goalMinutes > 0)
     */
    public List<DailyGoal> findPendingEmailAlerts(LocalDate date) {
        return repo
            .findAllByGoalDateAndNotificationEnabledTrueAndEmailAlertSentFalseAndGoalReachedEmailSentFalseAndGoalMinutesGreaterThan(
                date, 0);
    }

    /**
     * Marks a DailyGoal's missed-goal email as sent.
     * Only call this after Resend confirms the email was dispatched.
     */
    @Transactional
    public void markEmailAlertSent(DailyGoal goal) {
        goal.setEmailAlertSent(true);
        goal.setEmailAlertSentAt(LocalDateTime.now(TORONTO));
        repo.save(goal);
        log.info("[EMAIL] Marked missed-goal email sent — goalId={} userId={} date={}",
                goal.getId(), goal.getUser().getId(), goal.getGoalDate());
    }

    // ── Queries ──────────────────────────────────────────────────────────────────

    /** Returns today's DailyGoal for the user, or empty if none exists. */
    public Optional<DailyGoal> getTodayGoal(User user) {
        return repo.findByUserAndGoalDate(user, LocalDate.now(TORONTO));
    }

    // ── Private helpers ───────────────────────────────────────────────────────────

    private DailyGoal findOrCreate(User user, LocalDate date) {
        return repo.findByUserAndGoalDate(user, date).orElseGet(() -> {
            DailyGoal g = new DailyGoal();
            g.setUser(user);
            g.setGoalDate(date);
            return g;
        });
    }
}
