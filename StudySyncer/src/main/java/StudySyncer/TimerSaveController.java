package StudySyncer;

import StudySyncer.dto.TimerStateDto;
import StudySyncer.entity.TimerProgress;
import StudySyncer.entity.User;
import StudySyncer.repository.TimerProgressRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Full-state timer REST API.
 *
 * Endpoints
 * ─────────
 *   GET  /api/timer/state                 — read current state
 *   POST /api/timer/start                 — start / resume current phase
 *   POST /api/timer/pause                 — pause current phase
 *   POST /api/timer/stop                  — finalize session (user-triggered end)
 *     body: { phase: string, actualDurationSeconds: int,
 *             plannedSeconds: int, overtimeSeconds: int }
 *   POST /api/timer/skip                  — skip to next phase (no auto-start)
 *   POST /api/timer/phase                 — switch phase
 *     body: { phase: string }
 *   POST /api/timer/durations             — update pomodoro / break durations
 *     body: { pomodoroMinutes, shortBreakMinutes, longBreakMinutes }
 *   POST /api/timer/countdown             — apply custom countdown duration
 *     body: { minutes: int }
 *
 * Legacy endpoints (kept for backward compat with older clients / tests)
 *   POST /api/timer/save                  — write mode + totalSeconds + sessionCount
 *   GET  /api/timer/load                  — read same three fields
 *   POST /api/timer/complete              — legacy no-op, returns current state
 *
 * Guests get 401 silently; the client swallows it and runs in localStorage mode.
 */
@RestController
@RequestMapping("/api/timer")
public class TimerSaveController {

    private final TimerProgressRepository timerRepo;
    private final TimerStateService       timerStateService;
    private final UserService             userService;

    public TimerSaveController(TimerProgressRepository timerRepo,
                               TimerStateService timerStateService,
                               UserService userService) {
        this.timerRepo         = timerRepo;
        this.timerStateService = timerStateService;
        this.userService       = userService;
    }

    // ── Full state REST API ───────────────────────────────────────────

    @GetMapping("/state")
    public ResponseEntity<?> getState(HttpSession session) {
        User user = resolveUser(session);
        if (user == null) return unauthorized();
        return ResponseEntity.ok(timerStateService.getState(user));
    }

    @PostMapping("/start")
    public ResponseEntity<?> start(HttpSession session) {
        User user = resolveUser(session);
        if (user == null) return unauthorized();
        return ResponseEntity.ok(timerStateService.start(user));
    }

    @PostMapping("/pause")
    public ResponseEntity<?> pause(HttpSession session) {
        User user = resolveUser(session);
        if (user == null) return unauthorized();
        return ResponseEntity.ok(timerStateService.pause(user));
    }

    @PostMapping("/skip")
    public ResponseEntity<?> skip(HttpSession session) {
        User user = resolveUser(session);
        if (user == null) return unauthorized();
        return ResponseEntity.ok(timerStateService.skip(user));
    }

    @PostMapping("/stop")
    public ResponseEntity<?> stop(@RequestBody(required = false) Map<String, Object> body,
                                  HttpSession session) {
        User user = resolveUser(session);
        if (user == null) return unauthorized();

        Integer actual = null;
        if (body != null) {
            Object raw = body.get("actualDurationSeconds");
            if (raw instanceof Number) {
                actual = ((Number) raw).intValue();
            } else if (raw instanceof String && !((String) raw).isEmpty()) {
                try { actual = Integer.parseInt((String) raw); } catch (NumberFormatException ignored) {}
            }
        }
        return ResponseEntity.ok(timerStateService.stop(user, actual));
    }

    /** Legacy no-op kept so any lingering client doesn't 404 in overtime mode. */
    @PostMapping("/complete")
    public ResponseEntity<?> complete(HttpSession session) {
        User user = resolveUser(session);
        if (user == null) return unauthorized();
        return ResponseEntity.ok(timerStateService.getState(user));
    }

    @PostMapping("/phase")
    public ResponseEntity<?> phase(@RequestBody Map<String, String> body,
                                   HttpSession session) {
        User user = resolveUser(session);
        if (user == null) return unauthorized();
        return ResponseEntity.ok(timerStateService.setPhase(user, body.get("phase")));
    }

    @PostMapping("/durations")
    public ResponseEntity<?> durations(@RequestBody Map<String, Integer> body,
                                       HttpSession session) {
        User user = resolveUser(session);
        if (user == null) return unauthorized();
        // New client sends targetMinutes; legacy client sent pomodoroMinutes.
        Integer target = body.get("targetMinutes");
        Integer pomodoro = target != null ? target : body.get("pomodoroMinutes");
        return ResponseEntity.ok(timerStateService.setDurations(
                user,
                pomodoro,
                body.get("shortBreakMinutes"),
                body.get("longBreakMinutes")));
    }

    @PostMapping("/countdown")
    public ResponseEntity<?> countdown(@RequestBody Map<String, Integer> body,
                                       HttpSession session) {
        User user = resolveUser(session);
        if (user == null) return unauthorized();
        Integer m = body.get("minutes");
        if (m == null) m = 25;
        return ResponseEntity.ok(timerStateService.applyCountdown(user, m));
    }

    // ── Legacy endpoints ──────────────────────────────────────────────

    /** POST /api/timer/save — legacy, writes three scalar fields. */
    @PostMapping("/save")
    @Transactional
    public ResponseEntity<?> save(@RequestBody TimerStateDto dto, HttpSession session) {
        User user = resolveUser(session);
        if (user == null) return unauthorized();

        TimerProgress p = timerRepo.findByUser(user).orElse(new TimerProgress());
        p.setUser(user);
        if (dto.getMode() != null)        p.setMode(dto.getMode());
        if (dto.getTotalSeconds() > 0)    p.setTotalSeconds(dto.getTotalSeconds());
        if (dto.getSessionCount() > 0)    p.setSessionCount(dto.getSessionCount());
        p.setUpdatedAt(LocalDateTime.now());
        timerRepo.save(p);

        return ResponseEntity.ok(Map.of("message", "Timer saved."));
    }

    /** GET /api/timer/load — legacy, reads three scalar fields. */
    @GetMapping("/load")
    public ResponseEntity<?> load(HttpSession session) {
        User user = resolveUser(session);
        if (user == null) return unauthorized();

        return timerRepo.findByUser(user)
                .<ResponseEntity<?>>map(p -> {
                    TimerStateDto dto = new TimerStateDto();
                    dto.setMode(p.getMode());
                    dto.setTotalSeconds(p.getTotalSeconds());
                    dto.setSessionCount(p.getSessionCount());
                    return ResponseEntity.ok(dto);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private ResponseEntity<?> unauthorized() {
        return ResponseEntity.status(401).body(Map.of("error", "Not logged in."));
    }

    private User resolveUser(HttpSession session) {
        Long id = AuthController.resolveUserId(session);
        if (id == null) return null;
        return userService.findById(id).orElse(null);
    }
}
