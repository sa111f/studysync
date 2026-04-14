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
 * Overtime-aware model
 * ────────────────────
 *   plannedSeconds       configured phase length
 *   elapsedBaseSeconds   elapsed accumulated from prior running segments
 *                        (authoritative while !running)
 *   runStartAtMs         when the current running segment began
 *
 *   While running, true elapsed = elapsedBaseSeconds + (now - runStartAtMs).
 *   Reaching zero does NOT finalize; only an explicit stop() call does.
 *
 * Session logging (POST /api/tracker/sessions) is intentionally NOT invoked
 * here — it remains the frontend UI layer's responsibility when it receives
 * a sessionEnd event from TimerCore.
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
        return toDto(getOrCreate(user));
    }

    // ── Start / pause ─────────────────────────────────────────────────

    /** Start or resume. No-op if already running. */
    @Transactional
    public TimerStateDto start(User user) {
        TimerProgress p = getOrCreate(user);
        if (p.isRunning()) return toDto(p);

        if (p.getPlannedSeconds() <= 0) {
            p.setPlannedSeconds(durationFor(p, p.getPhase()));
        }
        p.setRunStartAtMs(System.currentTimeMillis());
        p.setRunning(true);
        p.setPaused(false);
        touch(p);
        repo.save(p);
        return toDto(p);
    }

    @Transactional
    public TimerStateDto pause(User user) {
        TimerProgress p = getOrCreate(user);
        if (!p.isRunning()) return toDto(p);

        int elapsed = computeElapsed(p);
        p.setElapsedBaseSeconds(elapsed);
        p.setRunning(false);
        p.setPaused(true);
        p.setRunStartAtMs(null);
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
        p.setPlannedSeconds(total);
        p.setTotalSeconds(total);
        p.setElapsedBaseSeconds(0);
        p.setRemainingSeconds(total);
        p.setRunning(false);
        p.setPaused(false);
        p.setRunStartAtMs(null);
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

        if (!"countdown".equals(p.getPhase())) {
            int total = durationFor(p, p.getPhase());
            p.setPlannedSeconds(total);
            p.setTotalSeconds(total);
            p.setElapsedBaseSeconds(0);
            p.setRemainingSeconds(total);
            p.setRunning(false);
            p.setPaused(false);
            p.setRunStartAtMs(null);
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

        int total = m * 60;
        p.setPhase("countdown");
        p.setMode("countdown");
        p.setPlannedSeconds(total);
        p.setTotalSeconds(total);
        p.setElapsedBaseSeconds(0);
        p.setRemainingSeconds(total);
        p.setRunning(false);
        p.setPaused(false);
        p.setRunStartAtMs(null);
        p.setRunEndAtMs(null);
        touch(p);
        repo.save(p);
        return toDto(p);
    }

    // ── Stop & skip ───────────────────────────────────────────────────

    /**
     * Finalize the session (manual stop). Advances to the next phase in
     * stopped state. This is the only way a pomodoro/countdown session
     * is considered done.
     */
    @Transactional
    public TimerStateDto stop(User user, Integer actualDurationSeconds) {
        TimerProgress p = getOrCreate(user);
        String endingPhase = p.getPhase();
        int elapsed = actualDurationSeconds != null && actualDurationSeconds >= 0
                ? actualDurationSeconds
                : computeElapsed(p);
        int planned = p.getPlannedSeconds();

        TimerStateDto dto = advancePhase(p, endingPhase);
        dto.setCompletedPhase(endingPhase);
        dto.setCompletedSeconds(elapsed);
        dto.setShouldLogStudy("pomodoro".equals(endingPhase) || "countdown".equals(endingPhase));
        log.debug("[TIMER] stop phase={} elapsed={}s planned={}s", endingPhase, elapsed, planned);
        return dto;
    }

    /** Skip = advance to next phase without treating it as a full completion. */
    @Transactional
    public TimerStateDto skip(User user) {
        TimerProgress p = getOrCreate(user);
        String endingPhase = p.getPhase();
        int elapsed = computeElapsed(p);

        TimerStateDto dto = advancePhase(p, endingPhase);
        boolean loggable = ("pomodoro".equals(endingPhase) || "countdown".equals(endingPhase))
                           && elapsed >= 60;
        dto.setCompletedPhase(endingPhase);
        dto.setCompletedSeconds(elapsed);
        dto.setShouldLogStudy(loggable);
        return dto;
    }

    // ── Internal helpers ──────────────────────────────────────────────

    /** Elapsed seconds of the current session, live-computed while running. */
    private int computeElapsed(TimerProgress p) {
        int base = Math.max(0, p.getElapsedBaseSeconds());
        if (p.isRunning() && p.getRunStartAtMs() != null) {
            long delta = System.currentTimeMillis() - p.getRunStartAtMs();
            if (delta < 0) delta = 0;
            base += (int) (delta / 1000L);
        }
        return base;
    }

    private TimerStateDto advancePhase(TimerProgress p, String endingPhase) {
        String next;
        if ("pomodoro".equals(endingPhase)) {
            int justFinished = Math.max(1, p.getSessionCount());
            next = (justFinished % 4 == 0) ? "longbreak" : "shortbreak";
            p.setSessionCount(justFinished + 1);
        } else if ("shortbreak".equals(endingPhase) || "longbreak".equals(endingPhase)) {
            if (p.getSessionCount() > 4) p.setSessionCount(1);
            next = "pomodoro";
        } else {
            next = "countdown";
        }

        p.setPhase(next);
        p.setMode("countdown".equals(next) ? "countdown" : "pomodoro");
        int total = durationFor(p, next);
        p.setPlannedSeconds(total);
        p.setTotalSeconds(total);
        p.setElapsedBaseSeconds(0);
        p.setRemainingSeconds(total);
        p.setRunning(false);
        p.setPaused(false);
        p.setRunStartAtMs(null);
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
            p.setPlannedSeconds(25 * 60);
            p.setTotalSeconds(25 * 60);
            p.setElapsedBaseSeconds(0);
            p.setRemainingSeconds(25 * 60);
            p.setSessionCount(1);
            p.setRunning(false);
            p.setPaused(false);
            p.setRunStartAtMs(null);
            p.setRunEndAtMs(null);
            p.setUpdatedAt(LocalDateTime.now());
            return repo.save(p);
        });
    }

    private int durationFor(TimerProgress p, String phase) {
        if ("shortbreak".equals(phase)) return safeMin(p.getShortBreakMinutes(), 5)  * 60;
        if ("longbreak".equals(phase))  return safeMin(p.getLongBreakMinutes(),  15) * 60;
        if ("countdown".equals(phase)) {
            int ts = p.getPlannedSeconds() > 0 ? p.getPlannedSeconds() : p.getTotalSeconds();
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

        int planned = Math.max(0, p.getPlannedSeconds());
        if (planned <= 0) planned = Math.max(0, p.getTotalSeconds());
        int base    = Math.max(0, p.getElapsedBaseSeconds());
        int remain  = Math.max(0, planned - base);

        // Legacy
        dto.setMode(p.getMode() != null ? p.getMode() : "pomodoro");
        dto.setTotalSeconds(planned);
        dto.setSessionCount(Math.max(1, p.getSessionCount()));
        dto.setRemainingSeconds(remain);

        // Full state
        dto.setPhase(p.getPhase() != null ? p.getPhase() : "pomodoro");
        dto.setPomodoroMinutes(safeMin(p.getPomodoroMinutes(),   25));
        dto.setShortBreakMinutes(safeMin(p.getShortBreakMinutes(), 5));
        dto.setLongBreakMinutes(safeMin(p.getLongBreakMinutes(),  15));
        dto.setRunning(p.isRunning());
        dto.setPaused(p.isPaused());
        dto.setPlannedSeconds(planned);
        dto.setElapsedBase(base);
        dto.setRunStartAtMs(p.getRunStartAtMs());
        dto.setRunEndAtMs(null);
        dto.setServerNowMs(System.currentTimeMillis());
        return dto;
    }
}
