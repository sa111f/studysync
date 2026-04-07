package StudySyncer;

import StudySyncer.entity.DailyGoal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * End-of-day scheduler that sends accountability SMS messages.
 *
 * Default schedule: 23:59 every day in the configured timezone.
 * Override via environment variables:
 *   NOTIFICATION_CRON      — Spring cron expression (6-part: s m h d M W)
 *   NOTIFICATION_TIMEZONE  — Java timezone ID (e.g. "America/New_York", "UTC")
 *
 * The scheduler is idempotent: it skips any record where notificationSent=true,
 * so running it more than once for a given day is safe.
 */
@Component
public class AccountabilityScheduler {

    private static final Logger log = LoggerFactory.getLogger(AccountabilityScheduler.class);

    private final DailyGoalService dailyGoalService;
    private final SmsService       smsService;

    public AccountabilityScheduler(DailyGoalService dailyGoalService, SmsService smsService) {
        this.dailyGoalService = dailyGoalService;
        this.smsService       = smsService;
    }

    /**
     * Runs once a day at the time defined by {@code studysyncer.notification.cron}
     * in the zone defined by {@code studysyncer.notification.timezone}.
     *
     * For each pending DailyGoal record it:
     *  1. Decides whether the goal was achieved (completedMinutes >= goalMinutes).
     *  2. Builds the appropriate SMS message using the user's username.
     *  3. Sends the SMS via Twilio.
     *  4. If successful, marks notificationSent=true and stores the timestamp + message.
     *     If Twilio fails, the record remains unsent — a retry is safe because of the
     *     notificationSent guard.
     */
    @Scheduled(
        cron     = "${studysyncer.notification.cron:0 59 23 * * *}",
        zone     = "${studysyncer.notification.timezone:America/New_York}"
    )
    public void sendDailyAccountabilityNotifications() {
        LocalDate today = LocalDate.now();
        log.info("[SCHEDULER] Running accountability check for date={}", today);

        List<DailyGoal> pending = dailyGoalService.findPendingNotifications(today);
        log.info("[SCHEDULER] Found {} pending notification(s) for {}", pending.size(), today);

        int sent  = 0;
        int failed = 0;

        for (DailyGoal goal : pending) {
            try {
                processGoal(goal);
                sent++;
            } catch (Exception e) {
                // Never let one failure stop the rest of the batch
                log.error("[SCHEDULER] Unexpected error processing goalId={}: {}",
                        goal.getId(), e.getMessage(), e);
                failed++;
            }
        }

        log.info("[SCHEDULER] Done for {} — sent={} failed={}", today, sent, failed);
    }

    // ── Manual trigger endpoint (for testing) ─────────────────────────────────

    /**
     * Allows triggering the scheduler manually for testing without waiting for 23:59.
     * Called by POST /api/admin/trigger-notifications (see DailyGoalController).
     * Also used internally by the cron job.
     */
    public void runNow() {
        sendDailyAccountabilityNotifications();
    }

    // ── Private logic ──────────────────────────────────────────────────────────

    private void processGoal(DailyGoal goal) {
        String username = goal.getUser().getUsername();
        String phone    = goal.getAccountabilityPhone();
        int    goalMin  = goal.getGoalMinutes();
        int    doneMin  = goal.getCompletedMinutes();

        // Guard: should already be filtered by the query, but be safe
        if (!goal.isNotificationEnabled() || !goal.isConsentConfirmed()) {
            log.warn("[SCHEDULER] Skipping goalId={} — notificationEnabled={} consentConfirmed={}",
                    goal.getId(), goal.isNotificationEnabled(), goal.isConsentConfirmed());
            return;
        }
        if (goal.isNotificationSent()) {
            log.info("[SCHEDULER] Skipping goalId={} — already sent", goal.getId());
            return;
        }
        if (phone == null || phone.isBlank()) {
            log.warn("[SCHEDULER] Skipping goalId={} userId={} — no phone number stored",
                    goal.getId(), goal.getUser().getId());
            return;
        }
        if (goalMin <= 0) {
            log.warn("[SCHEDULER] Skipping goalId={} — goalMinutes={} (no real goal set)",
                    goal.getId(), goalMin);
            return;
        }

        boolean achieved = doneMin >= goalMin;
        String message = achieved
                ? "StudySyncer alert: " + username + " reached today's study goal."
                : "StudySyncer alert: " + username + " didn't reach today's study goal.";

        log.info("[SCHEDULER] Sending SMS — goalId={} userId={} to={} achieved={} done={}min goal={}min",
                goal.getId(), goal.getUser().getId(), phone, achieved, doneMin, goalMin);

        boolean ok = smsService.send(phone, message);
        if (ok) {
            dailyGoalService.markNotificationSent(goal, message);
        } else {
            log.error("[SCHEDULER] SMS failed for goalId={} userId={} — will retry on next scheduler run",
                    goal.getId(), goal.getUser().getId());
        }
    }
}
