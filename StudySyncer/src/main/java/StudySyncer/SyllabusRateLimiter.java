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
 * Per-user sliding-window rate limiter for syllabus uploads.
 *
 * SEPARATE bucket from {@link AiRateLimiter} because a PDF extraction +
 * full-syllabus AI call is ~10× the token cost of a single task/exam parse.
 * Spec 7.3: 5 syllabus uploads per user per hour.
 *
 * Implementation mirrors AiRateLimiter exactly — two classes share the
 * same deque-per-user pattern; refactoring into a parameterized shared
 * base would save ~40 lines but adds Bean wiring complexity. Keeping the
 * clone for readability.
 *
 * 🚩 In-memory only — resets on restart, not shared across nodes.
 */
@Component
public class SyllabusRateLimiter {

    static final int      MAX_CALLS = 5;
    static final Duration WINDOW    = Duration.ofHours(1);

    private final Map<Long, Deque<Instant>> history = new ConcurrentHashMap<>();

    public Decision tryAcquire(long userId) {
        Instant now    = Instant.now();
        Instant cutoff = now.minus(WINDOW);

        Deque<Instant> deque = history.computeIfAbsent(userId, k -> new ArrayDeque<>());
        synchronized (deque) {
            Iterator<Instant> it = deque.iterator();
            while (it.hasNext()) {
                if (it.next().isBefore(cutoff)) it.remove();
                else break;
            }
            if (deque.size() >= MAX_CALLS) {
                Instant oldest = deque.peekFirst();
                long retry = oldest == null
                        ? WINDOW.getSeconds()
                        : Math.max(1, Duration.between(now, oldest.plus(WINDOW)).getSeconds());
                return new Decision(false, retry);
            }
            deque.addLast(now);
            return new Decision(true, 0L);
        }
    }

    /** Test hook. */
    void reset() { history.clear(); }

    public static final class Decision {
        private final boolean allowed;
        private final long    retryAfterSeconds;

        public Decision(boolean allowed, long retryAfterSeconds) {
            this.allowed           = allowed;
            this.retryAfterSeconds = retryAfterSeconds;
        }
        public boolean isAllowed()            { return allowed; }
        public long    getRetryAfterSeconds() { return retryAfterSeconds; }
    }
}
