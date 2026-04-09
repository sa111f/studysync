package StudySyncer;

import StudySyncer.entity.DailyGoal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * End-of-day scheduler that sends missed-goal accountability emails.
 *
 * Runs once per day at the configured time (default: 23:59 in the configured timezone).
 *
 * A record is processed only if ALL of the following are true:
 *   - the user enabled email accountability (notificationEnabled = true)
 *   - the missed-goal email has not been sent yet (emailAlertSent = false)
 *   - the goal-reached email has not been sent (goalReachedEmailSent = false)
 *     → goal was NOT reached during the day, so a missed-goal email may be appropriate
 *   - a real goal was set (goalMinutes > 0)
 *
 * The scheduler also performs a safety check on completedMinutes vs goalMinutes
 * before sending, in case a record slips through with the goal actually met.
 *
 * Override the schedule via environment variables:
 *   NOTIFICATION_CRON      — Spring cron expression (6-part: s m h d M W)
 *   NOTIFICATION_TIMEZONE  — Java timezone ID (e.g. "America/New_York", "UTC")
 *
 * Every run is idempotent: emailAlertSent and goalReachedEmailSent flags prevent
 * duplicate sends even if the scheduler fires more than once for a given day.
 */
@Component
public class AccountabilityScheduler {

    private static final Logger log = LoggerFactory.getLogger(AccountabilityScheduler.class);

    private final DailyGoalService dailyGoalService;
    private final EmailService     emailService;

    // Constructor injection — preferred over @Autowired field injection
    public AccountabilityScheduler(DailyGoalService dailyGoalService,
                                   EmailService emailService) {
        this.dailyGoalService = dailyGoalService;
        this.emailService     = emailService;
    }

    /**
     * Runs once a day at the time defined by studysyncer.notification.cron
     * in the zone defined by studysyncer.notification.timezone.
     *
     * At this point, any record still in the pending list means:
     *   - the user opted in to email accountability
     *   - the goal-reached email was NOT sent (goal was never crossed during the day)
     *   - the missed-goal email has not been sent yet
     *
     * We then confirm completedMinutes < goalMinutes before sending (safety net).
     */
    @Scheduled(
        cron     = "${studysyncer.notification.cron:0 59 23 * * *}",
        zone     = "${studysyncer.notification.timezone:America/Toronto}"
    )
    public void sendDailyAccountabilityNotifications() {
        // Use Toronto date so the scheduler fires and queries for the correct
        // local calendar day, not the UTC date (which would be the next day
        // after 8 PM Toronto time during EDT).
        LocalDate today = LocalDate.now(ZoneId.of("America/Toronto"));
        log.info("[SCHEDULER] Running missed-goal email check for date={}", today);

        // Fetch records that may need a missed-goal email
        // (accountability enabled, no goal-reached email sent, no missed-goal email sent yet)
        List<DailyGoal> pending = dailyGoalService.findPendingEmailAlerts(today);
        log.info("[SCHEDULER] Missed-goal batch: {} pending for {}", pending.size(), today);

        int sent = 0, skipped = 0, failed = 0;
        for (DailyGoal goal : pending) {
            try {
                boolean emailSent = processEmailGoal(goal);
                if (emailSent) sent++; else skipped++;
            } catch (Exception e) {
                // Never let one failure stop the rest of the batch
                log.error("[SCHEDULER] Unexpected error for goalId={}: {}",
                        goal.getId(), e.getMessage(), e);
                failed++;
            }
        }
        log.info("[SCHEDULER] Missed-goal batch done for {} — sent={} skipped={} failed={}",
                today, sent, skipped, failed);
    }

    /**
     * Allows triggering the scheduler manually for testing without waiting for 23:59.
     * Called by POST /api/daily-goal/trigger-notifications.
     */
    public void runNow() {
        sendDailyAccountabilityNotifications();
    }

    // ── Private logic ──────────────────────────────────────────────────────────

    /**
     * Handles the missed-goal email for a single DailyGoal record.
     *
     * Logic:
     *   - If goalReachedEmailSent = true → goal was reached during the day → skip.
     *     (This shouldn't appear in the query results, but we check as a safety net.)
     *   - If completedMinutes >= goalMinutes → goal was met → skip without sending.
     *   - Otherwise → send missed-goal email.
     *
     * Email recipient priority:
     *   1. accountabilityEmail stored on the goal
     *   2. user's registered email address
     *   3. ALERT_TO_EMAIL fallback (handled inside EmailService)
     *
     * @return true if a missed-goal email was dispatched, false if skipped
     */
    private boolean processEmailGoal(DailyGoal goal) {
        // Safety net: if the goal was reached (success email sent), do not send missed-goal email
        if (goal.isGoalReachedEmailSent()) {
            log.info("[SCHEDULER] Skipping goalId={} — goal was reached earlier today",
                    goal.getId());
            return false;
        }

        // Idempotency guard (query already filtered, but be safe)
        if (goal.isEmailAlertSent()) {
            log.info("[SCHEDULER] Skipping goalId={} — missed-goal email already sent",
                    goal.getId());
            return false;
        }

        int goalMin = goal.getGoalMinutes();
        int doneMin = goal.getCompletedMinutes();

        // Safety check: if the goal was actually met by end of day, skip without sending
        if (doneMin >= goalMin) {
            log.info("[SCHEDULER] Skipping goalId={} — goal met at end of day (done={}min goal={}min)",
                    goal.getId(), doneMin, goalMin);
            // Mark to avoid re-checking this record in future scheduler runs
            dailyGoalService.markEmailAlertSent(goal);
            return false;
        }

        String userName = goal.getUser().getUsername();

        // Resolve recipient:
        //   1. Per-day accountability email on the goal
        //   2. User's persistent accountability email (set via "Set Email" button)
        //   3. User's registered email address
        //   EmailService handles the final fallback to ALERT_TO_EMAIL.
        String recipientEmail = goal.getAccountabilityEmail();
        if (recipientEmail == null || recipientEmail.isBlank()) {
            recipientEmail = goal.getUser().getAccountabilityEmail();
        }
        if (recipientEmail == null || recipientEmail.isBlank()) {
            recipientEmail = goal.getUser().getEmail();
        }

        log.info("[SCHEDULER] Sending missed-goal email — goalId={} userId={} done={}min goal={}min",
                goal.getId(), goal.getUser().getId(), doneMin, goalMin);

        boolean ok = emailService.sendMissedGoalEmail(
                recipientEmail, userName, goalMin, doneMin, goal.getGoalDate());

        if (ok) {
            dailyGoalService.markEmailAlertSent(goal);
            return true;
        } else {
            log.error("[SCHEDULER] Email failed for goalId={} userId={} — will retry on next run",
                    goal.getId(), goal.getUser().getId());
            return false;
        }
    }
}
