package StudySyncer;

import com.resend.Resend;
import com.resend.services.emails.model.CreateEmailOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

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

    /**
     * Constructor injection — values come from application.properties,
     * which reads the environment variables.
     */
    public EmailService(
            @Value("${resend.api-key:}") String apiKey,
            @Value("${resend.from-email:}") String fromEmail,
            @Value("${resend.alert-to-email:}") String fallbackToEmail) {
        this.apiKey          = apiKey;
        this.fromEmail       = fromEmail;
        this.fallbackToEmail = fallbackToEmail;
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
                    .subject("StudySync: Daily goal reached")
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
                    .subject("StudySync: Daily goal missed")
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
