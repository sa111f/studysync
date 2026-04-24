package StudySyncer;

import StudySyncer.entity.EmailType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Objects;

/**
 * Signs and verifies one-click unsubscribe tokens.
 *
 * Token format (URL-safe Base64 of "userId:emailType:expiryEpoch:hmac"):
 *   {userId}:{emailType}:{expiryEpoch}:{HMAC-SHA256 of the first three fields}
 *
 * The HMAC is keyed with {@code studysyncer.unsubscribe.secret}. Expiry is
 * absolute (no clock skew tolerance — the window is 90 days).
 *
 * Why a signed token and not a DB table of unsubscribe keys?
 *   - No extra write on email send (hot path — scheduler fires on every tick)
 *   - No storage grows unbounded
 *   - Rotating the secret invalidates all outstanding links (easy kill-switch
 *     if a shared mailbox is compromised)
 */
@Service
public class UnsubscribeTokenService {

    private static final Logger log = LoggerFactory.getLogger(UnsubscribeTokenService.class);
    private static final String HMAC_ALG = "HmacSHA256";

    private final byte[] secretBytes;
    private final long   expiryDays;

    public UnsubscribeTokenService(
            @Value("${studysyncer.unsubscribe.secret:}") String secret,
            @Value("${studysyncer.unsubscribe.expiry-days:90}") long expiryDays) {
        if (secret == null || secret.isBlank()) {
            // Never leave the service silently unkeyed — if prod forgets the
            // env var, fail loudly at startup rather than issue unsigned links.
            throw new IllegalStateException(
                    "studysyncer.unsubscribe.secret is not configured. Set UNSUBSCRIBE_SECRET.");
        }
        this.secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        this.expiryDays  = Math.max(1, expiryDays);
    }

    // ── Sign ──────────────────────────────────────────────────────────

    /**
     * Produce a token good for {@link #expiryDays} days that, when posted
     * back to {@link UnsubscribeController}, disables one email type for
     * one user. URL-safe Base64 encoding so it survives email-client link
     * rewriting.
     */
    public String sign(long userId, EmailType emailType) {
        long expiry = Instant.now().plus(expiryDays, ChronoUnit.DAYS).getEpochSecond();
        String payload = userId + ":" + emailType.name() + ":" + expiry;
        String sig = hmac(payload);
        String combined = payload + ":" + sig;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(combined.getBytes(StandardCharsets.UTF_8));
    }

    // ── Verify ────────────────────────────────────────────────────────

    /**
     * Verify a token. Returns a {@link Verification} that indicates which
     * of the three possible outcomes occurred:
     *   - VALID    : signature + expiry OK, caller should disable the type
     *   - EXPIRED  : signature OK but the token is older than expiryDays
     *   - INVALID  : signature mismatch or malformed token
     *
     * Constant-time comparison on the HMAC (via {@link MessageDigest#isEqual},
     * wrapped here with a byte array compare) — prevents timing attacks on the
     * secret.
     */
    public Verification verify(String token) {
        if (token == null || token.isBlank()) return Verification.invalid();

        byte[] raw;
        try {
            raw = Base64.getUrlDecoder().decode(token);
        } catch (IllegalArgumentException bad) {
            return Verification.invalid();
        }
        String decoded = new String(raw, StandardCharsets.UTF_8);
        String[] parts = decoded.split(":");
        if (parts.length != 4) return Verification.invalid();

        long userId;
        EmailType emailType;
        long expiryEpoch;
        try {
            userId      = Long.parseLong(parts[0]);
            emailType   = EmailType.valueOf(parts[1]);
            expiryEpoch = Long.parseLong(parts[2]);
        } catch (Exception e) {
            return Verification.invalid();
        }

        String expectedSig = hmac(parts[0] + ":" + parts[1] + ":" + parts[2]);
        if (!timingSafeEquals(expectedSig, parts[3])) {
            return Verification.invalid();
        }

        if (Instant.ofEpochSecond(expiryEpoch).isBefore(Instant.now())) {
            return Verification.expired(userId, emailType);
        }

        return Verification.valid(userId, emailType);
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private String hmac(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(secretBytes, HMAC_ALG));
            byte[] out = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(out);
        } catch (Exception e) {
            // Only thrown if the JVM lacks HmacSHA256, which would mean a
            // broken install — crash is the right move.
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }

    private static boolean timingSafeEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] aa = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        if (aa.length != bb.length) return false;
        int diff = 0;
        for (int i = 0; i < aa.length; i++) diff |= aa[i] ^ bb[i];
        return diff == 0;
    }

    // ── Verification result ───────────────────────────────────────────

    public static final class Verification {
        public enum Status { VALID, EXPIRED, INVALID }

        private final Status status;
        private final Long userId;
        private final EmailType emailType;

        private Verification(Status s, Long u, EmailType e) {
            this.status = s; this.userId = u; this.emailType = e;
        }
        public Status    getStatus()    { return status; }
        public Long      getUserId()    { return userId; }
        public EmailType getEmailType() { return emailType; }
        public boolean   isValid()      { return status == Status.VALID; }
        public boolean   isExpired()    { return status == Status.EXPIRED; }

        static Verification valid(long userId, EmailType emailType) {
            return new Verification(Status.VALID,   userId, Objects.requireNonNull(emailType));
        }
        static Verification expired(long userId, EmailType emailType) {
            return new Verification(Status.EXPIRED, userId, emailType);
        }
        static Verification invalid() {
            return new Verification(Status.INVALID, null, null);
        }
    }
}
