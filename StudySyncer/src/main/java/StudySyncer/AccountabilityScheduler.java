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
 * Scheduled accountability email dispatcher.
 *
 * TWO scheduled jobs run here:
 *
 * 1. rollingRolloverCheck (every 15 minutes)
 *    Calls DailyGoalRolloverService.processRolloverForAllUsers(), which:
 *      - Finds all past DailyGoal records that still need missed-goal evaluation
 *      - Respects each user's stored IANA timezone to determine when midnight passed
 *      - Sends at most one missed-goal email per user per day (emailAlertSent flag)
 *    This is the PRIMARY check and handles:
 *      - Users who were offline at midnight
 *      - Users who refresh the page after midnight
 *      - Server restarts at midnight
 *      - Users in different timezones from the server
 *
 * 2. sendDailyAccountabilityNotifications (daily at 23:59 in America/Toronto)
 *    Legacy end-of-day check. Fires near the end of the Toronto day and processes
 *    any records where goalDate = today (before the rollover service would catch them
 *    after midnight). Kept as a belt-and-suspenders safety net.
 *
 * Both jobs are fully idempotent: the emailAlertSent and goalReachedEmailSent
 * flags prevent duplicate sends regardless of how many times either job runs.
 *
 * Override the daily schedule via environment variables:
 *   NOTIFICATION_CRON     — Spring cron expression (6-part: s m h d M W)
 *   NOTIFICATION_TIMEZONE — Java timezone ID (e.g. "America/Toronto")
 */
@Component
public class AccountabilityScheduler {

    private static final Logger log = LoggerFactory.getLogger(AccountabilityScheduler.class);

    private final DailyGoalService        dailyGoalService;
    private final DailyGoalRolloverService rolloverService;
    private final EmailService            emailService;

    public AccountabilityScheduler(DailyGoalService dailyGoalService,
                                   DailyGoalRolloverService rolloverService,
                                   EmailService emailService) {
        this.dailyGoalService = dailyGoalService;
        this.rolloverService  = rolloverService;
        this.emailService     = emailService;
    }

    // ── Job 1: Every 15 minutes — timezone-aware rollover for all users ─────────

    /**
     * Runs every 15 minutes and checks all users for missed goals after midnight
     * in their respective local timezones.
     *
     * This is the main mechanism for ensuring missed-goal emails are sent even
     * when the user was offline at midnight, or the server was in a different timezone.
     */
    @Scheduled(cron = "0 */15 * * * *")
    public void rollingRolloverCheck() {
        log.info("[SCHEDULER] 15-min rollover check — processing all users");
        rolloverService.processRolloverForAllUsers();
    }

    // ── Job 2: Daily at 23:59 (legacy belt-and-suspenders) ──────────────────────

    /**
     * Runs once a day at 23:59 in the configured timezone.
     * Catches any records for TODAY that the rollover service hasn't processed yet
     * (e.g. users who miss their goal right at the end of the day).
     *
     * This job uses the original per-date query logic and marks records so that
     * the rollover service won't double-send when it next runs.
     *
     * Override schedule via NOTIFICATION_CRON and NOTIFICATION_TIMEZONE env vars.
     */
    @Scheduled(
        cron = "${studysyncer.notification.cron:0 59 23 * * *}",
        zone = "${studysyncer.notification.timezone:America/Toronto}"
    )
    public void sendDailyAccountabilityNotifications() {
        LocalDate today = LocalDate.now(ZoneId.of("America/Toronto"));
        log.info("[SCHEDULER] Daily 23:59 check for date={}", today);

        List<DailyGoal> pending = dailyGoalService.findPendingEmailAlerts(today);
        log.info("[SCHEDULER] Daily 23:59 batch: {} pending for {}", pending.size(), today);

        int sent = 0, skipped = 0, failed = 0;
        for (DailyGoal goal : pending) {
            try {
                boolean emailSent = processEmailGoal(goal);
                if (emailSent) sent++; else skipped++;
            } catch (Exception e) {
                log.error("[SCHEDULER] Unexpected error for goalId={}: {}",
                        goal.getId(), e.getMessage(), e);
                failed++;
            }
        }
        log.info("[SCHEDULER] Daily 23:59 batch done for {} — sent={} skipped={} failed={}",
                today, sent, skipped, failed);
    }

    // ── Manual trigger (dev/testing) ─────────────────────────────────────────────

    /**
     * Allows triggering both checks manually for testing without waiting for their
     * scheduled times. Called by POST /api/daily-goal/trigger-notifications.
     */
    public void runNow() {
        log.info("[SCHEDULER] Manual trigger: running both rollover and daily checks");
        rolloverService.processRolloverForAllUsers();
        sendDailyAccountabilityNotifications();
    }

    // ── Private logic for the 23:59 daily job ────────────────────────────────────

    /**
     * Handles the missed-goal email for a single DailyGoal record (23:59 job only).
     * The rollover service handles its own sending logic independently.
     *
     * @return true if a missed-goal email was dispatched, false if skipped
     */
    private boolean processEmailGoal(DailyGoal goal) {
        if (goal.isGoalReachedEmailSent()) {
            log.info("[SCHEDULER] Skipping goalId={} — goal was reached earlier today", goal.getId());
            return false;
        }
        if (goal.isEmailAlertSent()) {
            log.info("[SCHEDULER] Skipping goalId={} — missed-goal email already sent", goal.getId());
            return false;
        }

        int goalMin = goal.getGoalMinutes();
        int doneMin = goal.getCompletedMinutes();

        if (doneMin >= goalMin) {
            log.info("[SCHEDULER] Skipping goalId={} — goal met at end of day (done={}min goal={}min)",
                    goal.getId(), doneMin, goalMin);
            dailyGoalService.markEmailAlertSent(goal);
            return false;
        }

        // Resolve recipient: goal-level email → persistent user email → skip
        String persistentAcctEmail = goal.getUser().getAccountabilityEmail();
        String recipientEmail      = goal.getAccountabilityEmail();
        if (recipientEmail == null || recipientEmail.isBlank()) {
            recipientEmail = persistentAcctEmail;
        }

        if (recipientEmail == null || recipientEmail.isBlank()) {
            log.warn("[SCHEDULER] No accountability email for goalId={} userId={} — skipping",
                    goal.getId(), goal.getUser().getId());
            return false;
        }

        log.info("[SCHEDULER] Sending missed-goal email — goalId={} userId={} done={}min goal={}min",
                goal.getId(), goal.getUser().getId(), doneMin, goalMin);

        boolean ok = emailService.sendMissedGoalEmail(
                recipientEmail, goal.getUser().getUsername(), goalMin, doneMin, goal.getGoalDate());

        if (ok) {
            dailyGoalService.markEmailAlertSent(goal);
            // Phase 8 retrofit — unified send-log row.
            emailService.recordGoalEmailSent(
                    goal.getUser(), StudySyncer.entity.EmailType.GOAL_MISSED, goal.getGoalDate());
            return true;
        } else {
            log.error("[SCHEDULER] Email failed for goalId={} — will retry on next run", goal.getId());
            return false;
        }
    }
}
