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
     * At this point, any record still pending (notificationSent=false) means the
     * user did NOT reach their goal — the success SMS would have been sent immediately
     * by DailyGoalService.addCompletedMinutes() the moment the threshold was crossed.
     *
     * Therefore this scheduler ONLY sends the failure message.
     *
     * For each pending DailyGoal record it:
     *  1. Verifies guards (notification enabled, consent, phone, goalMinutes > 0).
     *  2. Sends the failure SMS via Twilio.
     *  3. If successful, marks notificationSent=true with type="FAILURE".
     *     If Twilio fails, the record remains unsent (retry is safe — notificationSent guard).
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
        // If success was already sent during the day, this record won't appear in the
        // pending query (notificationSent=true). Double-check as a safety net.
        if (goal.isNotificationSent()) {
            log.info("[SCHEDULER] Skipping goalId={} — notification already sent (type={})",
                    goal.getId(), goal.getNotificationType());
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

        // At end-of-day, notificationSent=false means the goal was never reached
        // (success fires immediately upon crossing the threshold during the day).
        // Always send the failure message here.
        String message = "StudySyncer alert: " + username + " didn't reach today's study goal.";

        log.info("[SCHEDULER] Sending failure SMS — goalId={} userId={} to={} done={}min goal={}min",
                goal.getId(), goal.getUser().getId(), phone, doneMin, goalMin);

        boolean ok = smsService.send(phone, message);
        if (ok) {
            dailyGoalService.markNotificationSent(goal, message, "FAILURE");
        } else {
            log.error("[SCHEDULER] SMS failed for goalId={} userId={} — will retry on next scheduler run",
                    goal.getId(), goal.getUser().getId());
        }
    }
}
