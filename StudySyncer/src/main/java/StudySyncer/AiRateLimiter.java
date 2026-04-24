package StudySyncer;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sliding-window rate limiter — 20 requests per user per hour.
 *
 * Backing store: ConcurrentHashMap keyed on userId. Each value is a
 * bounded deque of the last N call timestamps; on every check we prune
 * stale entries (> 1h old) and inspect the size.
 *
 * 🚩 In-memory only — resets on restart, not shared across nodes.
 * Fine for StudySyncer's current single-instance Railway deploy. Swap to
 * Redis / a bucket store before scaling horizontally.
 */
@Component
public class AiRateLimiter {

    /** Max calls per rolling WINDOW per user. */
    static final int     MAX_CALLS = 20;
    static final Duration WINDOW   = Duration.ofHours(1);

    private final Map<Long, Deque<Instant>> history = new ConcurrentHashMap<>();

    /**
     * Attempts to record a call. If the user is under the limit, returns
     * a Decision with allowed=true; otherwise allowed=false and
     * retryAfterSeconds estimates when the oldest in-window call expires.
     */
    public Decision tryAcquire(long userId) {
        Instant now = Instant.now();
        Instant cutoff = now.minus(WINDOW);

        // computeIfAbsent + synchronized(deque) keeps mutation atomic
        // without pulling in a separate lock per key.
        Deque<Instant> deque = history.computeIfAbsent(userId, k -> new ArrayDeque<>());
        synchronized (deque) {
            // Drop entries older than the window.
            Iterator<Instant> it = deque.iterator();
            while (it.hasNext()) {
                if (it.next().isBefore(cutoff)) it.remove();
                else break;     // timestamps are in insertion order → safe to stop
            }

            if (deque.size() >= MAX_CALLS) {
                Instant oldest = deque.peekFirst();
                long retryAfter = oldest == null
                        ? WINDOW.getSeconds()
                        : Math.max(1, Duration.between(now, oldest.plus(WINDOW)).getSeconds());
                return new Decision(false, retryAfter);
            }

            deque.addLast(now);
            return new Decision(true, 0L);
        }
    }

    /** Visible for tests so state doesn't bleed across cases. */
    void reset() {
        history.clear();
    }

    public static final class Decision {
        private final boolean allowed;
        private final long    retryAfterSeconds;

        public Decision(boolean allowed, long retryAfterSeconds) {
            this.allowed           = allowed;
            this.retryAfterSeconds = retryAfterSeconds;
        }
        public boolean isAllowed()             { return allowed; }
        public long    getRetryAfterSeconds()  { return retryAfterSeconds; }
    }
}
