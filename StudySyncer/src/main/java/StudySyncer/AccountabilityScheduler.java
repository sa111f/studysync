package StudySyncer;

import StudySyncer.entity.DailyGoal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * End-of-day scheduler that sends accountability notifications.
 *
 * Runs two independent batches:
 *   1. SMS batch  — sends a Twilio SMS to the user's accountability contact
 *                   (only if the user opted in with notificationEnabled + consentConfirmed).
 *   2. Email batch — sends a Resend email to the user's own registered email address
 *                   (sent for any user who set a goal and missed it, no extra opt-in needed).
 *
 * Default schedule: 23:59 every day in the configured timezone.
 * Override via environment variables:
 *   NOTIFICATION_CRON      — Spring cron expression (6-part: s m h d M W)
 *   NOTIFICATION_TIMEZONE  — Java timezone ID (e.g. "America/New_York", "UTC")
 *
 * Both batches are idempotent: the notificationSent / emailAlertSent flags prevent
 * duplicate sends even if the scheduler is triggered more than once for a given day.
 */
@Component
public class AccountabilityScheduler {

    private static final Logger log = LoggerFactory.getLogger(AccountabilityScheduler.class);

    private final DailyGoalService dailyGoalService;
    private final SmsService       smsService;
    private final EmailService     emailService;

    // Constructor injection — preferred over @Autowired field injection
    public AccountabilityScheduler(DailyGoalService dailyGoalService,
                                   SmsService smsService,
                                   EmailService emailService) {
        this.dailyGoalService = dailyGoalService;
        this.smsService       = smsService;
        this.emailService     = emailService;
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

        // ── Batch 1: SMS notifications ──────────────────────────────────────
        List<DailyGoal> smsPending = dailyGoalService.findPendingNotifications(today);
        log.info("[SCHEDULER] SMS batch: {} pending for {}", smsPending.size(), today);

        int smsSent = 0, smsFailed = 0;
        for (DailyGoal goal : smsPending) {
            try {
                processSmSGoal(goal);
                smsSent++;
            } catch (Exception e) {
                // Never let one failure stop the rest of the batch
                log.error("[SCHEDULER] Unexpected error in SMS batch for goalId={}: {}",
                        goal.getId(), e.getMessage(), e);
                smsFailed++;
            }
        }
        log.info("[SCHEDULER] SMS batch done for {} — sent={} failed={}", today, smsSent, smsFailed);

        // ── Batch 2: Email alerts ────────────────────────────────────────────
        List<DailyGoal> emailPending = dailyGoalService.findPendingEmailAlerts(today);
        log.info("[SCHEDULER] Email batch: {} pending for {}", emailPending.size(), today);

        int emailSent = 0, emailFailed = 0;
        for (DailyGoal goal : emailPending) {
            try {
                processEmailGoal(goal);
                emailSent++;
            } catch (Exception e) {
                log.error("[SCHEDULER] Unexpected error in email batch for goalId={}: {}",
                        goal.getId(), e.getMessage(), e);
                emailFailed++;
            }
        }
        log.info("[SCHEDULER] Email batch done for {} — sent={} failed={}", today, emailSent, emailFailed);
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

    /** Handles the SMS accountability notification for a single DailyGoal record. */
    private void processSmSGoal(DailyGoal goal) {
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

    /**
     * Handles the email missed-goal alert for a single DailyGoal record.
     *
     * Logic:
     *   - Only send if the goal was actually missed (completedMinutes < goalMinutes).
     *   - Email goes to the user's registered email address; falls back to ALERT_TO_EMAIL.
     *   - emailAlertSent guard prevents duplicate sends (idempotent).
     */
    private void processEmailGoal(DailyGoal goal) {
        // Double-check idempotency guard (query already filtered, but be safe)
        if (goal.isEmailAlertSent()) {
            log.info("[EMAIL] Skipping goalId={} — email alert already sent", goal.getId());
            return;
        }

        int goalMin = goal.getGoalMinutes();
        int doneMin = goal.getCompletedMinutes();

        // Only alert if the goal was actually missed
        if (doneMin >= goalMin) {
            log.info("[EMAIL] Skipping goalId={} — goal was met (done={}min goal={}min)",
                    goal.getId(), doneMin, goalMin);
            // Mark as sent so we don't re-check this record tomorrow
            dailyGoalService.markEmailAlertSent(goal);
            return;
        }

        String userName  = goal.getUser().getUsername();
        String userEmail = goal.getUser().getEmail();   // may be null for OAuth-only users

        log.info("[EMAIL] Sending missed-goal alert — goalId={} userId={} done={}min goal={}min",
                goal.getId(), goal.getUser().getId(), doneMin, goalMin);

        // EmailService resolves recipient: uses userEmail if set, falls back to ALERT_TO_EMAIL
        boolean ok = emailService.sendMissedGoalEmail(
                userEmail, userName, goalMin, doneMin, goal.getGoalDate());

        if (ok) {
            dailyGoalService.markEmailAlertSent(goal);
        } else {
            log.error("[EMAIL] Email failed for goalId={} userId={} — will retry on next scheduler run",
                    goal.getId(), goal.getUser().getId());
        }
    }
}
