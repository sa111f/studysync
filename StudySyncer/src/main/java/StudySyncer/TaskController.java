package StudySyncer;

import StudySyncer.dto.StatusPatchRequest;
import StudySyncer.dto.TaskRequest;
import StudySyncer.dto.TaskResponse;
import StudySyncer.entity.Task;
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
import java.util.stream.Collectors;

/**
 * REST API for user-scoped tasks.
 *
 * All endpoints require an authenticated session; unauthenticated requests
 * receive 401 with the project's standard {"error": "..."} body. Ownership
 * checks live in TaskService — this controller does not re-check.
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private static final Logger log = LoggerFactory.getLogger(TaskController.class);

    private final TaskService taskService;
    private final UserService userService;

    public TaskController(TaskService taskService, UserService userService) {
        this.taskService = taskService;
        this.userService = userService;
    }

    // ── Create ────────────────────────────────────────────────────────

    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody TaskRequest req, HttpSession session) {
        User user = resolveUser(session);
        if (user == null) return unauthorized();

        Task saved = taskService.create(user, req);
        TaskResponse body = TaskResponse.from(saved, userLocalToday(user));

        return ResponseEntity
                .created(URI.create("/api/tasks/" + saved.getId()))
                .body(body);
    }

    // ── List ──────────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<?> list(
            @RequestParam(required = false, defaultValue = "all") String status,
            HttpSession session) {
        User user = resolveUser(session);
        if (user == null) return unauthorized();

        LocalDate today = userLocalToday(user);
        List<Task> tasks = taskService.listForUser(user, status, today);

        List<TaskResponse> body = tasks.stream()
                .map(t -> TaskResponse.from(t, today))
                .collect(Collectors.toList());
        return ResponseEntity.ok(body);
    }

    // ── Get one ───────────────────────────────────────────────────────

    @GetMapping("/{id}")
    public ResponseEntity<?> getOne(@PathVariable Long id, HttpSession session) {
        User user = resolveUser(session);
        if (user == null) return unauthorized();

        Task t = taskService.get(user, id);
        return ResponseEntity.ok(TaskResponse.from(t, userLocalToday(user)));
    }

    // ── Full update ───────────────────────────────────────────────────

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id,
                                    @Valid @RequestBody TaskRequest req,
                                    HttpSession session) {
        User user = resolveUser(session);
        if (user == null) return unauthorized();

        Task updated = taskService.update(user, id, req);
        return ResponseEntity.ok(TaskResponse.from(updated, userLocalToday(user)));
    }

    // ── Status patch ──────────────────────────────────────────────────

    @PatchMapping("/{id}/status")
    public ResponseEntity<?> patchStatus(@PathVariable Long id,
                                         @Valid @RequestBody StatusPatchRequest req,
                                         HttpSession session) {
        User user = resolveUser(session);
        if (user == null) return unauthorized();

        Task updated = taskService.updateStatus(user, id, req.getStatus());
        return ResponseEntity.ok(TaskResponse.from(updated, userLocalToday(user)));
    }

    // ── Delete ────────────────────────────────────────────────────────

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id, HttpSession session) {
        User user = resolveUser(session);
        if (user == null) return unauthorized();

        taskService.delete(user, id);
        return ResponseEntity.noContent().build();
    }

    // ── Local exception handlers ──────────────────────────────────────
    // Kept local to this controller (no @ControllerAdvice in the project).
    // The body shape matches the rest of the API: {"error": "..."}.

    /** 404 — "not found OR not yours". */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<?> handleNotFound(ResourceNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Not found."));
    }

    /** 400 — Bean-Validation errors on @Valid DTOs. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getDefaultMessage() != null ? fe.getDefaultMessage() : fe.getField() + " is invalid")
                .findFirst()
                .orElse("Invalid request.");
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }

    /** 400 — hand-thrown IllegalArgumentException from TaskService.update(). */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> handleBadArg(IllegalArgumentException e) {
        return ResponseEntity.badRequest()
                .body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Invalid request."));
    }

    /** 400 — malformed JSON or unparseable enum values (e.g. status="BOGUS"). */
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
     * User-local "today" used for the `overdue` flag and the overdue filter.
     * Mirrors DailyGoalController's timezone resolution: use the user's stored
     * IANA zone when set, otherwise the project-wide fallback "America/Toronto"
     * (matching DailyGoalRolloverService.parseTimezone behaviour).
     */
    private LocalDate userLocalToday(User user) {
        String tz = user.getTimezone();
        ZoneId zone;
        try {
            zone = (tz != null && !tz.isBlank()) ? ZoneId.of(tz) : ZoneId.of("America/Toronto");
        } catch (Exception e) {
            log.debug("[TASKS] Invalid stored timezone '{}' for userId={}, falling back", tz, user.getId());
            zone = ZoneId.of("America/Toronto");
        }
        return LocalDate.now(zone);
    }
}
