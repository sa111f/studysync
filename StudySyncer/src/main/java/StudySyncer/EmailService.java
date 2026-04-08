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
 * Sends transactional emails via the Resend Java SDK.
 *
 * Required environment variables:
 *   RESEND_API_KEY   — your Resend API key (starts with "re_...")
 *   APP_FROM_EMAIL   — verified sender address, e.g. "StudySyncer <alerts@yourdomain.com>"
 *   ALERT_TO_EMAIL   — fallback recipient when a user has no registered email
 *
 * The service is safe to use even if Resend is not configured:
 * every method checks isConfigured() first and logs a warning instead of crashing.
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    // Read credentials from environment variables via application.properties bindings
    private final String apiKey;
    private final String fromEmail;
    private final String fallbackToEmail;

    /**
     * Constructor injection keeps this class testable and avoids field-injection.
     * Values come from application.properties, which reads the environment variables.
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
     * Sends a "daily goal missed" email to the given address.
     *
     * @param toEmail          recipient email address (usually user.getEmail())
     * @param userName         the user's display name (used in the email body)
     * @param goalMinutes      the target they set for the day
     * @param completedMinutes how many minutes they actually studied
     * @param date             the date the goal was missed
     * @return true if the email was dispatched, false if it was skipped or failed
     */
    public boolean sendMissedGoalEmail(String toEmail, String userName,
                                       int goalMinutes, int completedMinutes,
                                       LocalDate date) {
        // Safety: do nothing if Resend credentials are missing
        if (!isConfigured()) {
            log.warn("[EMAIL] Resend is not configured (check RESEND_API_KEY / APP_FROM_EMAIL) " +
                     "— skipping missed-goal email for user={}", userName);
            return false;
        }

        // Resolve recipient: use provided address, fall back to ALERT_TO_EMAIL
        String recipient = (toEmail != null && !toEmail.isBlank()) ? toEmail : fallbackToEmail;
        if (recipient == null || recipient.isBlank()) {
            log.warn("[EMAIL] No recipient address available — skipping missed-goal email for user={}", userName);
            return false;
        }

        try {
            // Create a Resend client with the API key from the environment
            Resend resend = new Resend(apiKey);

            // Build the email options (from, to, subject, HTML body)
            CreateEmailOptions email = CreateEmailOptions.builder()
                    .from(fromEmail)
                    .to(List.of(recipient))
                    .subject("StudySyncer Alert: Daily Goal Missed")
                    .html(buildHtmlBody(userName, goalMinutes, completedMinutes, date))
                    .build();

            // Send — Resend throws on failure, which we catch below
            resend.emails().send(email);

            log.info("[EMAIL] Missed-goal alert sent — to={} user={} date={} done={}min goal={}min",
                    recipient, userName, date, completedMinutes, goalMinutes);
            return true;

        } catch (Exception e) {
            // Log the error but do NOT rethrow — email failure must never crash the scheduler
            log.error("[EMAIL] Failed to send missed-goal email — to={} user={} date={}: {}",
                    recipient, userName, date, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Returns true if both RESEND_API_KEY and APP_FROM_EMAIL are set.
     * The scheduler calls this before attempting to send.
     */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank()
            && fromEmail != null && !fromEmail.isBlank();
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    /**
     * Builds a clean HTML email body for the missed-goal alert.
     * The inline CSS keeps it readable in most email clients without external stylesheets.
     */
    private String buildHtmlBody(String userName, int goalMinutes,
                                  int completedMinutes, LocalDate date) {
        int shortfall = Math.max(0, goalMinutes - completedMinutes);
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="UTF-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1.0">
                  <style>
                    body {
                      font-family: Arial, sans-serif;
                      background-color: #f4f4f7;
                      margin: 0;
                      padding: 0;
                    }
                    .container {
                      max-width: 520px;
                      margin: 40px auto;
                      background-color: #ffffff;
                      border-radius: 8px;
                      padding: 32px;
                      box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
                    }
                    h2 {
                      color: #e53e3e;
                      margin-top: 0;
                      font-size: 20px;
                    }
                    p {
                      color: #333333;
                      line-height: 1.6;
                    }
                    .stats {
                      background-color: #f8f8f8;
                      border-radius: 6px;
                      padding: 16px 20px;
                      margin: 20px 0;
                    }
                    .stats p {
                      margin: 6px 0;
                    }
                    .stats .label {
                      color: #666666;
                      font-size: 14px;
                    }
                    .stats .value {
                      font-weight: bold;
                      color: #222222;
                    }
                    .cta {
                      margin-top: 24px;
                      font-size: 15px;
                    }
                    .footer {
                      margin-top: 32px;
                      font-size: 12px;
                      color: #aaaaaa;
                      border-top: 1px solid #eeeeee;
                      padding-top: 16px;
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
