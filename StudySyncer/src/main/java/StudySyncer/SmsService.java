package StudySyncer;

import com.twilio.Twilio;
import com.twilio.exception.ApiException;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.type.PhoneNumber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Sends SMS messages via Twilio.
 *
 * Required environment variables / application properties:
 *   TWILIO_ACCOUNT_SID   — Twilio Account SID
 *   TWILIO_AUTH_TOKEN    — Twilio Auth Token
 *   TWILIO_PHONE_NUMBER  — The Twilio phone number to send from (E.164, e.g. +12125551234)
 *
 * If any of these are blank the service logs an error and returns false
 * instead of throwing — the scheduler must not crash because SMS isn't configured.
 */
@Service
public class SmsService {

    private static final Logger log = LoggerFactory.getLogger(SmsService.class);

    @Value("${twilio.account-sid:}")
    private String accountSid;

    @Value("${twilio.auth-token:}")
    private String authToken;

    @Value("${twilio.phone-number:}")
    private String fromNumber;

    /**
     * Sends an SMS to {@code toNumber} with the given {@code body}.
     *
     * @return true if the SMS was accepted by Twilio, false on any failure.
     */
    public boolean send(String toNumber, String body) {
        if (accountSid == null || accountSid.isBlank()) {
            log.error("[SMS] TWILIO_ACCOUNT_SID is not configured — cannot send SMS.");
            return false;
        }
        if (authToken == null || authToken.isBlank()) {
            log.error("[SMS] TWILIO_AUTH_TOKEN is not configured — cannot send SMS.");
            return false;
        }
        if (fromNumber == null || fromNumber.isBlank()) {
            log.error("[SMS] TWILIO_PHONE_NUMBER is not configured — cannot send SMS.");
            return false;
        }
        if (toNumber == null || toNumber.isBlank()) {
            log.error("[SMS] Recipient phone number is blank — skipping send.");
            return false;
        }
        if (body == null || body.isBlank()) {
            log.error("[SMS] Message body is blank — skipping send.");
            return false;
        }

        try {
            Twilio.init(accountSid, authToken);
            Message message = Message.creator(
                    new PhoneNumber(toNumber),
                    new PhoneNumber(fromNumber),
                    body
            ).create();

            log.info("[SMS] Sent successfully — sid={} to={} status={}",
                    message.getSid(), toNumber, message.getStatus());
            return true;

        } catch (ApiException e) {
            log.error("[SMS] Twilio API error (code={}) sending to {}: {}",
                    e.getCode(), toNumber, e.getMessage());
            return false;
        } catch (Exception e) {
            log.error("[SMS] Unexpected error sending to {}: {}", toNumber, e.getMessage(), e);
            return false;
        }
    }
}
