package StudySyncer;

import StudySyncer.dto.TimerStateDto;
import StudySyncer.entity.TimerProgress;
import StudySyncer.entity.User;
import StudySyncer.repository.TimerProgressRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * The ONE owner of a user's timer state on the server.
 *
 * Why a dedicated service
 * ───────────────────────
 * Before this existed, timer state was split across localStorage (client) and
 * a sparse TimerProgress row (server, only the mode + session count).  Every
 * page reload re-derived elapsed time from a decrement-based counter that had
 * already been advanced by the live interval, producing a double-subtraction
 * that lost ~15 minutes per navigation cycle.
 *
 * The new model:
 *   - remainingSeconds is meaningful ONLY when the timer is not running
 *   - when running, (runEndAtMs - now) is the source of truth
 *   - transitions are atomic + transactional and wrap around a shared row
 *   - completePhase is idempotent: it refuses to advance unless the caller's
 *     expected phase and runEndAtMs still match the row, so two tabs hitting
 *     it at the same moment only advance once
 *
 * Session logging (POST /api/tracker/sessions) is intentionally NOT invoked
 * here — it remains the frontend UI layer's responsibility.  The client
 * receives completedPhase/completedSeconds in the /complete response and
 * decides whether to log.
 */
@Service
public class TimerStateService {

    private static final Logger log = LoggerFactory.getLogger(TimerStateService.class);

    private final TimerProgressRepository repo;

    public TimerStateService(TimerProgressRepository repo) {
        this.repo = repo;
    }

    // ── Read ──────────────────────────────────────────────────────────

    @Transactional
    public TimerStateDto getState(User user) {
        TimerProgress p = getOrCreate(user);
        return toDto(p);
    }

    // ── Start ─────────────────────────────────────────────────────────

    /**
     * Start or resume the timer.  No-op if already running.  The server
     * computes runEndAtMs from its OWN clock so all tabs agree on the
     * phase-end instant regardless of local clock skew.
     */
    @Transactional
    public TimerStateDto start(User user) {
        TimerProgress p = getOrCreate(user);
        if (p.isRunning()) return toDto(p);   // idempotent

        int remaining = Math.max(0, p.getRemainingSeconds());
        if (remaining <= 0) {
            // Stale zero-remaining state (e.g. we just hit 0 but never
            // received /complete).  Reset to full phase duration so the
            // user can actually start again.
            remaining = durationFor(p, p.getPhase());
            p.setRemainingSeconds(remaining);
        }

        long now = System.currentTimeMillis();
        p.setRunEndAtMs(now + (long) remaining * 1000L);
        p.setRunning(true);
        p.setPaused(false);
        touch(p);
        repo.save(p);
        return toDto(p);
    }

    // ── Pause ─────────────────────────────────────────────────────────

    @Transactional
    public TimerStateDto pause(User user) {
        TimerProgress p = getOrCreate(user);
        if (!p.isRunning()) return toDto(p);   // idempotent

        long now = System.currentTimeMillis();
        long endAt = p.getRunEndAtMs() != null ? p.getRunEndAtMs() : now;
        int remaining = (int) Math.max(0, Math.round((endAt - now) / 1000.0));
        p.setRemainingSeconds(remaining);
        p.setRunning(false);
        p.setPaused(true);
        p.setRunEndAtMs(null);
        touch(p);
        repo.save(p);
        return toDto(p);
    }

    // ── Phase & durations ─────────────────────────────────────────────

    @Transactional
    public TimerStateDto setPhase(User user, String phase) {
        String normalized = normalizePhase(phase);
        TimerProgress p = getOrCreate(user);

        p.setPhase(normalized);
        p.setMode("countdown".equals(normalized) ? "countdown" : "pomodoro");
        int total = durationFor(p, normalized);
        p.setTotalSeconds(total);
        p.setRemainingSeconds(total);
        p.setRunning(false);
        p.setPaused(false);
        p.setRunEndAtMs(null);
        touch(p);
        repo.save(p);
        return toDto(p);
    }

    @Transactional
    public TimerStateDto setDurations(User user,
                                      Integer pomodoro,
                                      Integer shortBreak,
                                      Integer longBreak) {
        TimerProgress p = getOrCreate(user);

        if (pomodoro   != null && pomodoro   >= 1 && pomodoro   <= 999) p.setPomodoroMinutes(pomodoro);
        if (shortBreak != null && shortBreak >= 1 && shortBreak <= 999) p.setShortBreakMinutes(shortBreak);
        if (longBreak  != null && longBreak  >= 1 && longBreak  <= 999) p.setLongBreakMinutes(longBreak);

        // If the user is currently on a work/break phase, pick up the new duration.
        // Countdown phases are custom per-material and must NOT be overwritten here.
        if (!"countdown".equals(p.getPhase())) {
            int total = durationFor(p, p.getPhase());
            p.setTotalSeconds(total);
            p.setRemainingSeconds(total);
            p.setRunning(false);
            p.setPaused(false);
            p.setRunEndAtMs(null);
        }
        touch(p);
        repo.save(p);
        return toDto(p);
    }

    @Transactional
    public TimerStateDto applyCountdown(User user, int minutes) {
        int m = Math.max(1, Math.min(999, minutes));
        TimerProgress p = getOrCreate(user);

        p.setPhase("countdown");
        p.setMode("countdown");
        int total = m * 60;
        p.setTotalSeconds(total);
        p.setRemainingSeconds(total);
        p.setRunning(false);
        p.setPaused(false);
        p.setRunEndAtMs(null);
        touch(p);
        repo.save(p);
        return toDto(p);
    }

    // ── Skip ──────────────────────────────────────────────────────────

    /**
     * Skip the current phase.  Records the elapsed portion so the UI can
     * optionally POST it as a partial session log, then advances to the
     * next phase in stopped state (frontend decides whether to auto-start).
     */
    @Transactional
    public TimerStateDto skip(User user) {
        TimerProgress p = getOrCreate(user);
        String endingPhase = p.getPhase();
        int total = p.getTotalSeconds();
        int remaining;

        if (p.isRunning() && p.getRunEndAtMs() != null) {
            long now = System.currentTimeMillis();
            remaining = (int) Math.max(0, Math.round((p.getRunEndAtMs() - now) / 1000.0));
        } else {
            remaining = p.getRemainingSeconds();
        }

        int elapsed = Math.max(0, total - remaining);

        TimerStateDto dto = advancePhase(p, endingPhase);
        // Flag for client: only study-like phases generate loggable elapsed
        boolean loggable = ("pomodoro".equals(endingPhase) || "countdown".equals(endingPhase))
                           && elapsed >= 60;
        dto.setCompletedPhase(endingPhase);
        dto.setCompletedSeconds(elapsed);
        dto.setShouldLogStudy(loggable);
        return dto;
    }

    // ── Complete (natural phase end, idempotent) ──────────────────────

    /**
     * Called by the client when its displayed remaining hits zero.  Protected
     * from duplicate calls by two checks:
     *
     *   1. Phase match — the caller's ended phase must equal the stored phase.
     *   2. runEndAtMs match (within 2 s tolerance) — the caller must have
     *      been running the SAME instance of the phase that's still in the DB.
     *
     * If either check fails, another tab or request already advanced the
     * state; we return the current row with shouldLogStudy=false so the
     * caller quietly drops its log attempt.
     */
    @Transactional
    public TimerStateDto completePhase(User user, String expectedPhase, Long expectedRunEndAtMs) {
        TimerProgress p = getOrCreate(user);
        String current = p.getPhase();
        Long currentEnd = p.getRunEndAtMs();

        boolean phaseMatches = expectedPhase != null && expectedPhase.equals(current);
        boolean endMatches;
        if (expectedRunEndAtMs == null || currentEnd == null) {
            // Either side null: if the row is still in the expected phase
            // and not running, accept.  This covers the case where the
            // client saw time hit zero after a pause-cycle.
            endMatches = phaseMatches && !p.isRunning();
        } else {
            endMatches = Math.abs(currentEnd - expectedRunEndAtMs) <= 2000L;
        }

        if (!phaseMatches || !endMatches) {
            log.debug("[TIMER] completePhase stale — expected phase={} endAt={} but row phase={} endAt={}",
                    expectedPhase, expectedRunEndAtMs, current, currentEnd);
            TimerStateDto dto = toDto(p);
            dto.setShouldLogStudy(false);
            return dto;
        }

        int total = durationFor(p, current);
        TimerStateDto dto = advancePhase(p, current);
        dto.setCompletedPhase(current);
        dto.setCompletedSeconds(total);
        dto.setShouldLogStudy("pomodoro".equals(current) || "countdown".equals(current));
        return dto;
    }

    // ── Internal helpers ──────────────────────────────────────────────

    /**
     * Core phase-advancement. Mutates the passed row in-place and persists.
     * Leaves the timer STOPPED; frontend is responsible for auto-starting
     * the next phase after a short grace period.
     */
    private TimerStateDto advancePhase(TimerProgress p, String endingPhase) {
        String next;
        if ("pomodoro".equals(endingPhase)) {
            // sessionCount is 1-based index of the current pomodoro
            int justFinished = Math.max(1, p.getSessionCount());
            next = (justFinished % 4 == 0) ? "longbreak" : "shortbreak";
            p.setSessionCount(justFinished + 1);
        } else if ("shortbreak".equals(endingPhase) || "longbreak".equals(endingPhase)) {
            if (p.getSessionCount() > 4) p.setSessionCount(1);
            next = "pomodoro";
        } else {
            // countdown — reset to full, stay on same phase
            next = "countdown";
        }

        p.setPhase(next);
        p.setMode("countdown".equals(next) ? "countdown" : "pomodoro");
        int total = durationFor(p, next);
        p.setTotalSeconds(total);
        p.setRemainingSeconds(total);
        p.setRunning(false);
        p.setPaused(false);
        p.setRunEndAtMs(null);
        touch(p);
        repo.save(p);
        return toDto(p);
    }

    private TimerProgress getOrCreate(User user) {
        return repo.findByUser(user).orElseGet(() -> {
            TimerProgress p = new TimerProgress();
            p.setUser(user);
            p.setMode("pomodoro");
            p.setPhase("pomodoro");
            p.setPomodoroMinutes(25);
            p.setShortBreakMinutes(5);
            p.setLongBreakMinutes(15);
            p.setTotalSeconds(25 * 60);
            p.setRemainingSeconds(25 * 60);
            p.setSessionCount(1);
            p.setRunning(false);
            p.setPaused(false);
            p.setRunEndAtMs(null);
            p.setUpdatedAt(LocalDateTime.now());
            return repo.save(p);
        });
    }

    private int durationFor(TimerProgress p, String phase) {
        if ("shortbreak".equals(phase)) return safeMin(p.getShortBreakMinutes(), 5)  * 60;
        if ("longbreak".equals(phase))  return safeMin(p.getLongBreakMinutes(),  15) * 60;
        if ("countdown".equals(phase)) {
            // Preserve whatever total was last set for the countdown phase.
            int ts = p.getTotalSeconds();
            return ts > 0 ? ts : safeMin(p.getPomodoroMinutes(), 25) * 60;
        }
        return safeMin(p.getPomodoroMinutes(), 25) * 60;
    }

    private int safeMin(int value, int fallback) {
        return (value >= 1 && value <= 999) ? value : fallback;
    }

    private String normalizePhase(String phase) {
        if (phase == null) return "pomodoro";
        switch (phase) {
            case "pomodoro": case "shortbreak":
            case "longbreak": case "countdown":
                return phase;
            default:
                return "pomodoro";
        }
    }

    private void touch(TimerProgress p) {
        p.setUpdatedAt(LocalDateTime.now());
    }

    private TimerStateDto toDto(TimerProgress p) {
        TimerStateDto dto = new TimerStateDto();
        // Legacy
        dto.setMode(p.getMode() != null ? p.getMode() : "pomodoro");
        dto.setTotalSeconds(p.getTotalSeconds());
        dto.setSessionCount(Math.max(1, p.getSessionCount()));

        // Full state
        dto.setPhase(p.getPhase() != null ? p.getPhase() : "pomodoro");
        dto.setPomodoroMinutes(safeMin(p.getPomodoroMinutes(),   25));
        dto.setShortBreakMinutes(safeMin(p.getShortBreakMinutes(), 5));
        dto.setLongBreakMinutes(safeMin(p.getLongBreakMinutes(),  15));
        dto.setRemainingSeconds(Math.max(0, p.getRemainingSeconds()));
        dto.setRunning(p.isRunning());
        dto.setPaused(p.isPaused());
        dto.setRunEndAtMs(p.getRunEndAtMs());
        dto.setServerNowMs(System.currentTimeMillis());
        return dto;
    }
}
