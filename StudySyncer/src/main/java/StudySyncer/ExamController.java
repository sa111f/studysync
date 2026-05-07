package StudySyncer;

import StudySyncer.dto.ExamRequest;
import StudySyncer.dto.ExamResponse;
import StudySyncer.entity.Exam;
import StudySyncer.entity.User;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * REST API for user-scoped exams.
 *
 * Mirrors TaskController's auth + error shape exactly:
 *   - Session-based auth (resolveUserId), 401 on missing session
 *   - 404 for "not found OR not yours" via ResourceNotFoundException
 *   - 400 on Bean Validation + hand-thrown IllegalArgumentException
 *   - Error body shape: {"error": "..."}
 */
@RestController
@RequestMapping("/api/exams")
public class ExamController {

    private static final Logger log = LoggerFactory.getLogger(ExamController.class);

    private final ExamService examService;
    private final UserService userService;

    public ExamController(ExamService examService, UserService userService) {
        this.examService = examService;
        this.userService = userService;
    }

    // ── Create ────────────────────────────────────────────────────────

    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody ExamRequest req, HttpSession session) {
        User user = resolveUser(session);
        if (user == null) return unauthorized();

        Exam saved = examService.create(user, req);
        ZoneId zone = userZone(user, req.getTimezone());
        LocalDate today = LocalDate.now(zone);
        return ResponseEntity
                .created(URI.create("/api/exams/" + saved.getId()))
                .body(ExamResponse.from(saved, today, zone));
    }

    // ── List ──────────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<?> list(
            @RequestParam(required = false, defaultValue = "all") String filter,
            @RequestParam(required = false) String tz,
            HttpSession session) {
        User user = resolveUser(session);
        if (user == null) return unauthorized();

        ZoneId zone = userZone(user, tz);
        LocalDate today = LocalDate.now(zone);

        List<Exam> rows = examService.listForUser(user, zone, filter);
        List<ExamResponse> body = rows.stream()
                .map(e -> ExamResponse.from(e, today, zone))
                .toList();
        return ResponseEntity.ok(body);
    }

    // ── Next N future exams (used by Dashboard) ───────────────────────

    @GetMapping("/next")
    public ResponseEntity<?> next(
            @RequestParam(required = false, defaultValue = "3") int count,
            @RequestParam(required = false) String tz,
            HttpSession session) {
        User user = resolveUser(session);
        if (user == null) return unauthorized();

        // Clamp to a sane range so a rogue client can't request 10_000.
        int n = Math.max(1, Math.min(20, count));

        ZoneId zone = userZone(user, tz);
        LocalDate today = LocalDate.now(zone);

        List<Exam> rows = examService.listNextN(user, n);
        List<ExamResponse> body = rows.stream()
                .map(e -> ExamResponse.from(e, today, zone))
                .toList();
        return ResponseEntity.ok(body);
    }

    // ── Get one ───────────────────────────────────────────────────────

    @GetMapping("/{id}")
    public ResponseEntity<?> getOne(
            @PathVariable Long id,
            @RequestParam(required = false) String tz,
            HttpSession session) {
        User user = resolveUser(session);
        if (user == null) return unauthorized();
        Exam e = examService.get(user, id);
        ZoneId zone = userZone(user, tz);
        return ResponseEntity.ok(ExamResponse.from(e, LocalDate.now(zone), zone));
    }

    // ── Full update ───────────────────────────────────────────────────

    @PutMapping("/{id}")
    public ResponseEntity<?> update(
            @PathVariable Long id,
            @Valid @RequestBody ExamRequest req,
            HttpSession session) {
        User user = resolveUser(session);
        if (user == null) return unauthorized();
        Exam updated = examService.update(user, id, req);
        ZoneId zone = userZone(user, req.getTimezone());
        return ResponseEntity.ok(ExamResponse.from(updated, LocalDate.now(zone), zone));
    }

    // ── Delete ────────────────────────────────────────────────────────

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id, HttpSession session) {
        User user = resolveUser(session);
        if (user == null) return unauthorized();
        examService.delete(user, id);
        return ResponseEntity.noContent().build();
    }

    // ── Local exception handlers (same pattern as TaskController) ─────

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<?> handleNotFound(ResourceNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Not found."));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getDefaultMessage() != null ? fe.getDefaultMessage() : fe.getField() + " is invalid")
                .findFirst()
                .orElse("Invalid request.");
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> handleBadArg(IllegalArgumentException e) {
        return ResponseEntity.badRequest()
                .body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Invalid request."));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> handleUnreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest()
                .body(Map.of("error", "Malformed request body."));
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private User resolveUser(HttpSession session) {
        Long id = AuthController.resolveUserId(session);
        if (id == null) return null;
        return userService.findById(id).orElse(null);
    }

    private ResponseEntity<?> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "Not logged in."));
    }

    /**
     * Resolve the effective timezone for a request: prefer an explicit
     * param (query string OR request body `timezone`), then the user's
     * stored timezone, falling back to America/Toronto (project default,
     * matching DailyGoalController).
     */
    private ZoneId userZone(User user, String paramTz) {
        if (paramTz != null && !paramTz.isBlank()) {
            try { return ZoneId.of(paramTz.trim()); }
            catch (Exception e) {
                log.debug("[EXAMS] Invalid tz param '{}' — falling back", paramTz);
            }
        }
        String stored = user.getTimezone();
        if (stored != null && !stored.isBlank()) {
            try { return ZoneId.of(stored); }
            catch (Exception ignored) { /* stored value invalid — fall through */ }
        }
        return TrackerService.TORONTO;
    }
}
