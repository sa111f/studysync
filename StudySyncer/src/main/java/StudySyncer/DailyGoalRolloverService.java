package StudySyncer;

import StudySyncer.entity.DailyGoal;
import StudySyncer.entity.User;
import StudySyncer.repository.DailyGoalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Handles day-boundary rollover for accountability emails.
 *
 * This service is the core of the "missed goal at midnight" logic. It is
 * intentionally separate from DailyGoalService to keep the rollover concern
 * isolated and easy to test.
 *
 * Rollover check: "Has midnight passed in the user's local timezone since
 * their last goal day? If so, and if they missed their goal, send the email."
 *
 * Called from multiple places (request paths + scheduler) so that rollover
 * is caught even when the user was offline at midnight or the server restarted:
 *   - GET  /api/daily-goal/today       (dashboard load)
 *   - POST /api/daily-goal             (goal settings update)
 *   - POST /api/tracker/sessions       (session save)
 *   - POST /api/auth/accountability-email (accountability email update)
 *   - AccountabilityScheduler          (every 15 minutes, all users)
 *
 * Idempotency: every method that sends or skips an email marks
 * DailyGoal.emailAlertSent = true so duplicate sends are impossible
 * regardless of how many times the rollover is triggered.
 *
 * Gap handling: if more than MAX_DAYS_BACK days have passed since a goal
 * was set, the goal is silently marked to avoid sending stale emails
 * that would confuse the user.
 */
@Service
public class DailyGoalRolloverService {

    private static final Logger log = LoggerFactory.getLogger(DailyGoalRolloverService.class);

    /** Default timezone when the user has no stored preference. */
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("America/Toronto");

    /**
     * Goals older than this many days are silently discarded rather than
     * triggering a late missed-goal email. Prevents stale/confusing emails
     * when a user comes back after a long break.
     */
    private static final int MAX_DAYS_BACK = 3;

    private final DailyGoalRepository repo;
    private final EmailService         emailService;

    public DailyGoalRolloverService(DailyGoalRepository repo, EmailService emailService) {
        this.repo         = repo;
        this.emailService = emailService;
    }

    // ── Request-path entry point ─────────────────────────────────────────────────

    /**
     * Processes day-boundary rollover for a single user. Typically called from
     * controller methods so that the user doesn't have to wait until the next
     * scheduler run.
     *
     * @param user   the logged-in user
     * @param zoneId the user's local timezone (resolved from browser Intl API)
     */
    public void processRolloverForUser(User user, ZoneId zoneId) {
        if (user == null) return;
        ZoneId zone  = (zoneId != null) ? zoneId : resolveUserZone(user);
        LocalDate today = LocalDate.now(zone);

        // Find all past goals for this user that still need evaluation.
        // "Before today" means midnight has passed in the user's timezone for those days.
        // Wrapped in try-catch so a transient DB issue (e.g. column not yet added on a
        // fresh deployment) never propagates to the caller and breaks page load.
        List<DailyGoal> pastGoals;
        try {
            pastGoals = repo.findAllByUserAndGoalDateBeforeAndNotificationEnabledTrueAndEmailAlertSentFalseAndGoalReachedEmailSentFalseAndGoalMinutesGreaterThan(
                    user, today, 0);
        } catch (Exception e) {
            log.error("[ROLLOVER] Failed to query past goals for userId={}: {}", user.getId(), e.getMessage(), e);
            return;
        }

        if (pastGoals.isEmpty()) return;

        LocalDate cutoff = today.minusDays(MAX_DAYS_BACK);
        log.debug("[ROLLOVER] Request-path check for userId={} timezone={} found {} past goals",
                user.getId(), zone.getId(), pastGoals.size());

        for (DailyGoal goal : pastGoals) {
            try {
                evaluateAndProcess(goal, today, cutoff);
            } catch (ObjectOptimisticLockingFailureException e) {
                // Another thread (scheduler or a concurrent request) already processed
                // this goal and committed the emailAlertSent flag first.  Our version
                // is now stale — skip silently; the email was already sent once.
                log.info("[ROLLOVER] Concurrent update for goalId={} userId={} — skipping " +
                                "(already processed by another thread)",
                        goal.getId(), user.getId());
            } catch (Exception e) {
                log.error("[ROLLOVER] Error processing goalId={} userId={} zone={}: {}",
                        goal.getId(), user.getId(), zone.getId(), e.getMessage(), e);
            }
        }
    }

    // ── Scheduler entry point ────────────────────────────────────────────────────

    /**
     * Processes day-boundary rollover for ALL users with pending accountability goals.
     * Called by the 15-minute periodic scheduler.
     *
     * Uses "tomorrow UTC" as the upper bound so that goals from any user timezone
     * (including UTC+14) are included in the initial query. Per-user timezone
     * refinement is applied before sending to ensure midnight has actually passed.
     */
    public void processRolloverForAllUsers() {
        // Query with tomorrow-UTC as cutoff so we catch ANY timezone's "yesterday or earlier".
        // The per-user midnight check below prevents premature sends for users whose day
        // hasn't ended yet.
        LocalDate tomorrowUtc = LocalDate.now(ZoneOffset.UTC).plusDays(1);

        List<DailyGoal> allPastGoals =
            repo.findAllByGoalDateBeforeAndNotificationEnabledTrueAndEmailAlertSentFalseAndGoalReachedEmailSentFalseAndGoalMinutesGreaterThan(
                tomorrowUtc, 0);

        if (allPastGoals.isEmpty()) {
            log.debug("[ROLLOVER] Scheduler: no pending goals found");
            return;
        }

        log.info("[ROLLOVER] Scheduler batch: {} goals to evaluate", allPastGoals.size());

        int sent = 0, skipped = 0, silenced = 0, errors = 0;
        for (DailyGoal goal : allPastGoals) {
            try {
                // Resolve timezone per user — honours stored preference
                ZoneId userZone  = resolveUserZone(goal.getUser());
                LocalDate today  = LocalDate.now(userZone);

                // Key gate: only proceed if midnight has actually passed in the user's timezone.
                // Without this, a scheduler run at 11 PM UTC would incorrectly process goals
                // for users whose local day hasn't ended (e.g. a user in America/Los_Angeles
                // where it's still 3 PM).
                if (!goal.getGoalDate().isBefore(today)) {
                    log.debug("[ROLLOVER] Scheduler: skipping goalId={} — not yet past midnight for userId={} (zone={})",
                            goal.getId(), goal.getUser().getId(), userZone.getId());
                    skipped++;
                    continue;
                }

                LocalDate cutoff = today.minusDays(MAX_DAYS_BACK);
                boolean emailSent = evaluateAndProcess(goal, today, cutoff);
                if (emailSent) sent++; else silenced++;

            } catch (ObjectOptimisticLockingFailureException e) {
                // Another thread already processed and committed this goal.
                // Our in-memory copy is stale — no duplicate email will be sent.
                log.info("[ROLLOVER] Scheduler: concurrent update for goalId={} — skipping " +
                                "(already processed by another thread)", goal.getId());
                skipped++;
            } catch (Exception e) {
                log.error("[ROLLOVER] Scheduler error for goalId={}: {}", goal.getId(), e.getMessage(), e);
                errors++;
            }
        }

        log.info("[ROLLOVER] Scheduler batch done — sent={} silenced={} skipped={} errors={}",
                sent, silenced, skipped, errors);
    }

    // ── Core rollover logic ──────────────────────────────────────────────────────

    /**
     * Evaluates a single past DailyGoal record and sends a missed-goal email
     * if all conditions are met.
     *
     * @param goal   the past goal to evaluate (goalDate must be before today)
     * @param today  the current local date in the user's timezone
     * @param cutoff the oldest date for which we will still send an email
     * @return true if a missed-goal email was dispatched
     */
    private boolean evaluateAndProcess(DailyGoal goal, LocalDate today, LocalDate cutoff) {
        // Sanity: goalDate must be before today (caller should guarantee this)
        if (!goal.getGoalDate().isBefore(today)) {
            return false;
        }

        // Idempotency: goal-reached email already sent means goal was met — skip
        if (goal.isGoalReachedEmailSent()) {
            markSilently(goal, "goal was reached");
            return false;
        }

        // Idempotency: missed-goal email already sent
        if (goal.isEmailAlertSent()) {
            return false;
        }

        int goalMin = goal.getGoalMinutes();
        int doneMin = goal.getCompletedMinutes();

        // If goal was met by end of day (but goalReachedEmailSent wasn't set, e.g. notification
        // was enabled after the threshold was already crossed), skip without sending missed email.
        if (doneMin >= goalMin) {
            log.info("[ROLLOVER] Goal was met for goalId={} (done={}min goal={}min) — marking without emailing",
                    goal.getId(), doneMin, goalMin);
            markSilently(goal, "goal was met at end of day");
            return false;
        }

        // Discard stale goals to avoid sending confusing late emails
        if (goal.getGoalDate().isBefore(cutoff)) {
            log.info("[ROLLOVER] goalId={} date={} is older than {} days — silently discarding",
                    goal.getId(), goal.getGoalDate(), MAX_DAYS_BACK);
            markSilently(goal, "too old — silently discarded");
            return false;
        }

        // Resolve recipient — same priority as goal-reached email:
        //   1. Per-day accountability email stored on the goal
        //   2. User's persistent accountability email (set via "Set Email" button)
        //   Never fall back to the login email.
        String recipient = goal.getAccountabilityEmail();
        if (recipient == null || recipient.isBlank()) {
            recipient = goal.getUser().getAccountabilityEmail();
        }

        if (recipient == null || recipient.isBlank()) {
            log.warn("[ROLLOVER] No accountability email set for goalId={} userId={} — skipping",
                    goal.getId(), goal.getUser().getId());
            // Don't mark as sent — if the user later sets an email, we don't want to lose
            // the pending record. However, if this happens every 15 minutes it would be noisy,
            // so we only log at WARN. The record stays unprocessed until an email is set.
            return false;
        }

        log.info("[ROLLOVER] Sending missed-goal email — goalId={} userId={} date={} done={}min goal={}min to={}",
                goal.getId(), goal.getUser().getId(), goal.getGoalDate(), doneMin, goalMin, recipient);

        boolean ok = emailService.sendMissedGoalEmail(
                recipient, goal.getUser().getUsername(), goalMin, doneMin, goal.getGoalDate());

        if (ok) {
            goal.setEmailAlertSent(true);
            goal.setEmailAlertSentAt(LocalDateTime.now());
            repo.save(goal);
            log.info("[ROLLOVER] Missed-goal email sent and recorded — goalId={} userId={}",
                    goal.getId(), goal.getUser().getId());
            return true;
        } else {
            // Email failed (Resend error, not configured, etc.) — EmailService already logged it.
            // Leave emailAlertSent=false so the next scheduler run retries.
            log.warn("[ROLLOVER] Email failed for goalId={} — will retry on next run", goal.getId());
            return false;
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────────

    /**
     * Marks a goal as processed (emailAlertSent=true) without sending an email.
     * Used to prevent the same goal from being re-evaluated on every scheduler run
     * when it is clear that no missed-goal email should be sent.
     */
    private void markSilently(DailyGoal goal, String reason) {
        if (!goal.isEmailAlertSent()) {
            goal.setEmailAlertSent(true);
            goal.setEmailAlertSentAt(LocalDateTime.now());
            repo.save(goal);
            log.debug("[ROLLOVER] Silently marked goalId={} (userId={}) — reason: {}",
                    goal.getId(), goal.getUser().getId(), reason);
        }
    }

    /**
     * Resolves the user's effective timezone.
     * Uses the stored IANA timezone if valid, falls back to DEFAULT_ZONE.
     */
    public ZoneId resolveUserZone(User user) {
        if (user == null) return DEFAULT_ZONE;
        String tz = user.getTimezone();
        if (tz != null && !tz.isBlank()) {
            try {
                return ZoneId.of(tz);
            } catch (Exception e) {
                log.warn("[ROLLOVER] Invalid stored timezone '{}' for userId={} — using default",
                        tz, user.getId());
            }
        }
        return DEFAULT_ZONE;
    }

    /**
     * Safely parses an IANA timezone string received from the browser.
     * Returns the default zone if the string is null, blank, or invalid.
     */
    public static ZoneId parseTimezone(String tz) {
        if (tz == null || tz.isBlank()) return DEFAULT_ZONE;
        try {
            return ZoneId.of(tz.strip());
        } catch (Exception e) {
            log.warn("[ROLLOVER] Received invalid timezone '{}' from client — using default", tz);
            return DEFAULT_ZONE;
        }
    }
}
