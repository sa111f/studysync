package StudySyncer;

import StudySyncer.entity.EmailSendLog;
import StudySyncer.entity.EmailType;
import StudySyncer.entity.User;
import StudySyncer.repository.EmailSendLogRepository;
import com.resend.Resend;
import com.resend.services.emails.model.CreateEmailOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * Sends transactional accountability emails via the Resend Java SDK.
 *
 * Two email types are supported:
 *   1. Goal-reached email  — sent immediately when the user crosses their daily goal threshold
 *   2. Missed-goal email   — sent at end of day when the goal was not reached
 *
 * Required environment variables:
 *   RESEND_API_KEY   — your Resend API key (starts with "re_...")
 *   APP_FROM_EMAIL   — verified sender address, e.g. "StudySyncer <alerts@yourdomain.com>"
 *   ALERT_TO_EMAIL   — fallback recipient when a user has no registered email
 *
 * Safe to use even when Resend is not configured: every send method checks
 * isConfigured() first and logs a warning instead of throwing.
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final String apiKey;
    private final String fromEmail;
    private final String fallbackToEmail;
    private final EmailSendLogRepository sendLogRepo;

    /**
     * Retry schedule for transient failures (spec 8.5).
     * Permanent failures (4xx / invalid address) bail out on the first
     * attempt — see {@link #isPermanentFailure(Throwable)}.
     */
    private static final long[] RETRY_DELAYS_MS = { 0L, 5_000L, 30_000L };

    /**
     * Constructor injection — values come from application.properties,
     * which reads the environment variables.
     */
    public EmailService(
            @Value("${resend.api-key:}") String apiKey,
            @Value("${resend.from-email:}") String fromEmail,
            @Value("${resend.alert-to-email:}") String fallbackToEmail,
            EmailSendLogRepository sendLogRepo) {
        this.apiKey          = apiKey;
        this.fromEmail       = fromEmail;
        this.fallbackToEmail = fallbackToEmail;
        this.sendLogRepo     = sendLogRepo;
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Sends a "daily goal reached" success email immediately when the user
     * crosses their goal threshold during the day.
     *
     * @param toEmail          recipient address (usually goal.accountabilityEmail or user.email)
     * @param userName         the user's display name
     * @param goalMinutes      the target they set for the day
     * @param completedMinutes how many minutes they actually studied
     * @param date             today's date
     * @return true if the email was dispatched, false if skipped or failed
     */
    public boolean sendGoalReachedEmail(String toEmail, String userName,
                                        int goalMinutes, int completedMinutes,
                                        LocalDate date) {
        if (!isConfigured()) {
            log.warn("[EMAIL] Resend not configured — skipping goal-reached email for user={}", userName);
            return false;
        }

        String recipient = resolveRecipient(toEmail);
        if (recipient == null) {
            log.warn("[EMAIL] No recipient available — skipping goal-reached email for user={}", userName);
            return false;
        }

        try {
            Resend resend = new Resend(apiKey);

            CreateEmailOptions email = CreateEmailOptions.builder()
                    .from(fromEmail)
                    .to(List.of(recipient))
                    .subject("StudySyncer: Daily goal reached")
                    .html(buildGoalReachedHtml(userName, goalMinutes, completedMinutes, date))
                    .build();

            resend.emails().send(email);

            log.info("[EMAIL] Goal-reached email sent — to={} user={} date={} done={}min goal={}min",
                    recipient, userName, date, completedMinutes, goalMinutes);
            return true;

        } catch (Exception e) {
            // Never rethrow — email failure must not crash the timer or session-save flow
            log.error("[EMAIL] Failed to send goal-reached email — to={} user={} date={}: {}",
                    recipient, userName, date, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Sends a "daily goal missed" email at end of day when the user did not
     * reach their study goal.
     *
     * @param toEmail          recipient address (usually goal.accountabilityEmail or user.email)
     * @param userName         the user's display name
     * @param goalMinutes      the target they set for the day
     * @param completedMinutes how many minutes they actually studied
     * @param date             the date the goal was missed
     * @return true if the email was dispatched, false if skipped or failed
     */
    public boolean sendMissedGoalEmail(String toEmail, String userName,
                                       int goalMinutes, int completedMinutes,
                                       LocalDate date) {
        if (!isConfigured()) {
            log.warn("[EMAIL] Resend not configured — skipping missed-goal email for user={}", userName);
            return false;
        }

        String recipient = resolveRecipient(toEmail);
        if (recipient == null) {
            log.warn("[EMAIL] No recipient available — skipping missed-goal email for user={}", userName);
            return false;
        }

        try {
            Resend resend = new Resend(apiKey);

            CreateEmailOptions email = CreateEmailOptions.builder()
                    .from(fromEmail)
                    .to(List.of(recipient))
                    .subject("StudySyncer: Daily goal missed")
                    .html(buildMissedGoalHtml(userName, goalMinutes, completedMinutes, date))
                    .build();

            resend.emails().send(email);

            log.info("[EMAIL] Missed-goal email sent — to={} user={} date={} done={}min goal={}min",
                    recipient, userName, date, completedMinutes, goalMinutes);
            return true;

        } catch (Exception e) {
            // Never rethrow — email failure must not crash the scheduler
            log.error("[EMAIL] Failed to send missed-goal email — to={} user={} date={}: {}",
                    recipient, userName, date, e.getMessage(), e);
            return false;
        }
    }

    // ══════════════════════════════════════════════════════════
    //  Phase 8 — unified notification sender
    // ══════════════════════════════════════════════════════════

    /**
     * Dispatches a Phase-8 notification (digest / overdue / exam reminder)
     * with retry + send-log semantics.
     *
     * Contract:
     *   - Caller has already passed their idempotency check (sendLog lookup,
     *     daily cap). This method doesn't re-check — it trusts the scheduler.
     *   - On SUCCESS: inserts an {@link EmailSendLog} row and returns true.
     *   - On PERMANENT FAILURE: logs WARN, returns false, NO send-log row.
     *     The scheduler's per-type retry window kicks in on the next tick,
     *     but since the send-log isn't written the "already sent today" guard
     *     won't short-circuit. Good for transient-looking 4xx too — the spec
     *     opts us into re-offering after a settings change or manual retry.
     *   - On TRANSIENT FAILURE: retries per {@link #RETRY_DELAYS_MS} then
     *     returns false.
     *
     * Never throws.
     */
    public boolean sendNotification(User user, EmailType type, Long referenceId,
                                    String subject, String htmlBody, String plainTextBody) {

        if (!isConfigured()) {
            log.warn("[EMAIL] Resend not configured — skipping {} for userId={}",
                    type, user.getId());
            return false;
        }
        String recipient = user.getAccountabilityEmail();
        if (recipient == null || recipient.isBlank()) {
            // Spec is explicit: do NOT fall back to u.email for Phase 8 emails.
            log.warn("[EMAIL] No accountability email — skipping {} for userId={}",
                    type, user.getId());
            return false;
        }

        boolean ok = sendWithRetries(recipient, subject, htmlBody, plainTextBody, type, user.getId());
        if (ok) {
            EmailSendLog row = new EmailSendLog();
            row.setUser(user);
            row.setEmailType(type);
            row.setReferenceId(referenceId);
            sendLogRepo.save(row);
            log.info("[EMAIL] Sent {} to userId={} ref={}", type, user.getId(), referenceId);
        }
        return ok;
    }

    /**
     * Retrofit hook (spec 8.1): record an {@link EmailSendLog} row for
     * pre-Phase-8 goal emails that already succeeded via the legacy path.
     * Safe to call multiple times — the unique constraint on
     * (userId, emailType, referenceId) dedupes.
     *
     * referenceId for goal emails is the date-code (YYYYMMDD) of the goalDate.
     */
    public void recordGoalEmailSent(User user, EmailType type, LocalDate goalDate) {
        if (user == null || type == null || goalDate == null) return;
        long refId = dateCode(goalDate);
        if (sendLogRepo.existsByUserAndEmailTypeAndReferenceId(user, type, refId)) return;
        try {
            EmailSendLog row = new EmailSendLog();
            row.setUser(user);
            row.setEmailType(type);
            row.setReferenceId(refId);
            sendLogRepo.save(row);
        } catch (Exception e) {
            // Race: another thread inserted between existsBy and save. Unique
            // constraint raised — log and move on. No user-visible effect.
            log.debug("[EMAIL] recordGoalEmailSent duplicate skipped userId={} type={}: {}",
                    user.getId(), type, e.getMessage());
        }
    }

    /** YYYYMMDD as a long — callable from NotificationScheduler too. */
    public static long dateCode(LocalDate date) {
        return (long) date.getYear() * 10000L
             + (long) date.getMonthValue() * 100L
             + (long) date.getDayOfMonth();
    }

    // ── Retry core ────────────────────────────────────────────────────

    private boolean sendWithRetries(String to, String subject, String html, String text,
                                    EmailType type, long userId) {
        Throwable last = null;
        for (int attempt = 0; attempt < RETRY_DELAYS_MS.length; attempt++) {
            long delay = RETRY_DELAYS_MS[attempt];
            if (delay > 0) {
                try { Thread.sleep(delay); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            try {
                CreateEmailOptions.Builder builder = CreateEmailOptions.builder()
                        .from(fromEmail)
                        .to(List.of(to))
                        .subject(subject)
                        .html(html);
                if (text != null && !text.isBlank()) {
                    builder = builder.text(text);
                }
                new Resend(apiKey).emails().send(builder.build());
                return true;
            } catch (Exception e) {
                last = e;
                if (isPermanentFailure(e)) {
                    log.warn("[EMAIL] Permanent failure (no retry) — type={} userId={}: {}",
                            type, userId, e.getMessage());
                    return false;
                }
                log.info("[EMAIL] Transient failure attempt {} — type={} userId={}: {}",
                        attempt + 1, type, userId, e.getMessage());
            }
        }
        log.warn("[EMAIL] Exhausted retries — type={} userId={}: {}",
                type, userId, last != null ? last.getMessage() : "unknown");
        return false;
    }

    /**
     * Best-effort permanent-vs-transient classifier. The Resend Java SDK
     * doesn't expose a clean status-code accessor across all error types,
     * so we do a message sniff:
     *   - Anything mentioning "validation" / "invalid" / "format" → permanent
     *   - 4xx-looking codes (400, 401, 403, 404, 422) → permanent
     *   - Everything else (5xx, network, timeout) → transient, retry
     *
     * False positives are cheap (an extra retry on a bad address), false
     * negatives too (the 5/day cap contains the damage).
     */
    private static boolean isPermanentFailure(Throwable e) {
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        if (msg.contains("validation")) return true;
        if (msg.contains("invalid"))    return true;
        if (msg.contains("not valid"))  return true;
        // HTTP-code heuristics in the message.
        if (msg.contains("400") || msg.contains("401") || msg.contains("403")
                || msg.contains("404") || msg.contains("422")) return true;
        return false;
    }

    /**
     * Returns true if both RESEND_API_KEY and APP_FROM_EMAIL are configured.
     */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank()
            && fromEmail != null && !fromEmail.isBlank();
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    /**
     * Resolves the email recipient. Uses the provided address if non-blank,
     * otherwise falls back to ALERT_TO_EMAIL. Returns null if neither is set.
     */
    private String resolveRecipient(String toEmail) {
        if (toEmail != null && !toEmail.isBlank()) return toEmail;
        if (fallbackToEmail != null && !fallbackToEmail.isBlank()) return fallbackToEmail;
        return null;
    }

    /**
     * Builds the HTML body for the goal-reached success email.
     * Inline CSS keeps it readable in most email clients.
     */
    private String buildGoalReachedHtml(String userName, int goalMinutes,
                                         int completedMinutes, LocalDate date) {
        int extra = Math.max(0, completedMinutes - goalMinutes);
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="UTF-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1.0">
                  <style>
                    body { font-family: Arial, sans-serif; background-color: #f4f4f7; margin: 0; padding: 0; }
                    .container {
                      max-width: 520px; margin: 40px auto; background-color: #ffffff;
                      border-radius: 8px; padding: 32px; box-shadow: 0 2px 8px rgba(0,0,0,0.08);
                    }
                    h2 { color: #22c55e; margin-top: 0; font-size: 20px; }
                    p { color: #333333; line-height: 1.6; }
                    .stats {
                      background-color: #f0fdf4; border-radius: 6px;
                      padding: 16px 20px; margin: 20px 0;
                    }
                    .stats p { margin: 6px 0; }
                    .label { color: #666666; font-size: 14px; }
                    .value { font-weight: bold; color: #222222; }
                    .cta { margin-top: 24px; font-size: 15px; }
                    .footer {
                      margin-top: 32px; font-size: 12px; color: #aaaaaa;
                      border-top: 1px solid #eeeeee; padding-top: 16px;
                    }
                  </style>
                </head>
                <body>
                  <div class="container">
                    <h2>Daily Goal Reached!</h2>
                    <p>Congrats, <strong>%s</strong>!</p>
                    <p>You hit your daily study goal on <strong>%s</strong>.</p>
                    <div class="stats">
                      <p><span class="label">Goal set:</span> <span class="value">%d minutes</span></p>
                      <p><span class="label">Completed:</span> <span class="value">%d minutes</span></p>
                      %s
                    </div>
                    <p class="cta">
                      Keep up the consistency — every session builds your streak!
                    </p>
                    <div class="footer">
                      You received this because you set a daily study goal in StudySyncer.
                    </div>
                  </div>
                </body>
                </html>
                """.formatted(
                userName,
                date,
                goalMinutes,
                completedMinutes,
                extra > 0
                    ? "<p><span class=\"label\">Extra time:</span> <span class=\"value\">" + extra + " minutes above goal</span></p>"
                    : ""
        );
    }

    /**
     * Builds the HTML body for the missed-goal alert email.
     */
    private String buildMissedGoalHtml(String userName, int goalMinutes,
                                        int completedMinutes, LocalDate date) {
        int shortfall = Math.max(0, goalMinutes - completedMinutes);
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="UTF-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1.0">
                  <style>
                    body { font-family: Arial, sans-serif; background-color: #f4f4f7; margin: 0; padding: 0; }
                    .container {
                      max-width: 520px; margin: 40px auto; background-color: #ffffff;
                      border-radius: 8px; padding: 32px; box-shadow: 0 2px 8px rgba(0,0,0,0.08);
                    }
                    h2 { color: #e53e3e; margin-top: 0; font-size: 20px; }
                    p { color: #333333; line-height: 1.6; }
                    .stats {
                      background-color: #f8f8f8; border-radius: 6px;
                      padding: 16px 20px; margin: 20px 0;
                    }
                    .stats p { margin: 6px 0; }
                    .label { color: #666666; font-size: 14px; }
                    .value { font-weight: bold; color: #222222; }
                    .cta { margin-top: 24px; font-size: 15px; }
                    .footer {
                      margin-top: 32px; font-size: 12px; color: #aaaaaa;
                      border-top: 1px solid #eeeeee; padding-top: 16px;
                    }
                  </style>
                </head>
                <body>
                  <div class="container">
                    <h2>Daily Study Goal Missed</h2>
                    <p>Hi <strong>%s</strong>,</p>
                    <p>Your daily study goal was not completed on <strong>%s</strong>.</p>
                    <div class="stats">
                      <p><span class="label">Goal set:</span> <span class="value">%d minutes</span></p>
                      <p><span class="label">Completed:</span> <span class="value">%d minutes</span></p>
                      <p><span class="label">Shortfall:</span> <span class="value">%d minutes</span></p>
                    </div>
                    <p class="cta">
                      Don't be discouraged — every day is a fresh start.
                      Log in to <strong>StudySyncer</strong> and keep building your streak!
                    </p>
                    <div class="footer">
                      You received this because you set a daily study goal in StudySyncer.
                    </div>
                  </div>
                </body>
                </html>
                """.formatted(userName, date, goalMinutes, completedMinutes, shortfall);
    }
}
