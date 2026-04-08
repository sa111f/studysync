package StudySyncer;

import StudySyncer.entity.DailyGoal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * End-of-day scheduler that sends accountability email notifications.
 *
 * Runs once per day at the configured time (default: 23:59 in the configured timezone).
 * For every user who:
 *   - set a daily study goal (goalMinutes > 0)
 *   - enabled email accountability (notificationEnabled = true)
 *   - missed their goal (completedMinutes < goalMinutes)
 *   - has not yet received an email alert today (emailAlertSent = false)
 *
 * ...the scheduler sends a missed-goal email via EmailService (Resend).
 *
 * Override the schedule via environment variables:
 *   NOTIFICATION_CRON      — Spring cron expression (6-part: s m h d M W)
 *   NOTIFICATION_TIMEZONE  — Java timezone ID (e.g. "America/New_York", "UTC")
 *
 * The emailAlertSent flag makes every run idempotent — safe to trigger multiple times.
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
     * At this point any record still pending (emailAlertSent=false) means the
     * user did NOT reach their goal by end of day — so we send the missed-goal email.
     */
    @Scheduled(
        cron = "${studysyncer.notification.cron:0 59 23 * * *}",
        zone = "${studysyncer.notification.timezone:America/New_York}"
    )
    public void sendDailyAccountabilityNotifications() {
        LocalDate today = LocalDate.now();
        log.info("[SCHEDULER] Running email accountability check for date={}", today);

        // Fetch all goals where the user enabled email accountability and alert not yet sent
        List<DailyGoal> pending = dailyGoalService.findPendingEmailAlerts(today);
        log.info("[SCHEDULER] Email batch: {} pending for {}", pending.size(), today);

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
        log.info("[SCHEDULER] Email batch done for {} — sent={} skipped={} failed={}",
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
     * Handles the email missed-goal alert for a single DailyGoal record.
     *
     * Logic:
     *   - Only send if the goal was actually missed (completedMinutes < goalMinutes).
     *   - If the goal was met, mark emailAlertSent so we skip this record next time.
     *   - Email goes to: (1) accountabilityEmail on the goal, (2) user's registered email,
     *     (3) ALERT_TO_EMAIL fallback (handled inside EmailService).
     *   - emailAlertSent guard prevents duplicates (idempotent).
     *
     * @return true if an email was dispatched, false if skipped (goal met)
     */
    private boolean processEmailGoal(DailyGoal goal) {
        // Idempotency guard (query already filtered, but be safe)
        if (goal.isEmailAlertSent()) {
            log.info("[SCHEDULER] Skipping goalId={} — email already sent", goal.getId());
            return false;
        }

        int goalMin = goal.getGoalMinutes();
        int doneMin = goal.getCompletedMinutes();

        // If the goal was met, mark it so we skip this record in future runs
        if (doneMin >= goalMin) {
            log.info("[SCHEDULER] Skipping goalId={} — goal was met (done={}min goal={}min)",
                    goal.getId(), doneMin, goalMin);
            dailyGoalService.markEmailAlertSent(goal);
            return false;
        }

        String userName = goal.getUser().getUsername();

        // Resolve recipient: prefer the accountability email stored on the goal,
        // then fall back to the user's registered email.
        // EmailService will further fall back to ALERT_TO_EMAIL if both are blank.
        String recipientEmail = goal.getAccountabilityEmail();
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
