package StudySyncer;

import StudySyncer.dto.DeadlineRequest;
import StudySyncer.dto.DeadlineResponse;
import StudySyncer.entity.User;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST API for deadline management.
 *
 * GET    /api/deadlines          → list all for current user
 * POST   /api/deadlines          → create
 * PUT    /api/deadlines/{id}     → update
 * DELETE /api/deadlines/{id}     → delete
 */
@RestController
@RequestMapping("/api/deadlines")
public class DeadlineController {

    private final DeadlineService deadlineService;
    private final UserService     userService;

    public DeadlineController(DeadlineService deadlineService, UserService userService) {
        this.deadlineService = deadlineService;
        this.userService     = userService;
    }

    @GetMapping
    public ResponseEntity<?> list(HttpSession session) {
        User user = resolveUser(session);
        if (user == null) return ResponseEntity.status(401).body(Map.of("error", "Not logged in."));
        List<DeadlineResponse> deadlines = deadlineService.getAll(user);
        return ResponseEntity.ok(deadlines);
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody DeadlineRequest req, HttpSession session) {
        User user = resolveUser(session);
        if (user == null) return ResponseEntity.status(401).body(Map.of("error", "Not logged in."));
        try {
            DeadlineResponse created = deadlineService.create(user, req);
            return ResponseEntity.ok(created);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id,
                                    @RequestBody DeadlineRequest req,
                                    HttpSession session) {
        User user = resolveUser(session);
        if (user == null) return ResponseEntity.status(401).body(Map.of("error", "Not logged in."));
        try {
            return deadlineService.update(user, id, req)
                    .<ResponseEntity<?>>map(ResponseEntity::ok)
                    .orElse(ResponseEntity.notFound().build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id, HttpSession session) {
        User user = resolveUser(session);
        if (user == null) return ResponseEntity.status(401).body(Map.of("error", "Not logged in."));
        boolean deleted = deadlineService.delete(user, id);
        if (!deleted) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of("message", "Deleted."));
    }

    // ── Helper ────────────────────────────────────────────

    private User resolveUser(HttpSession session) {
        Long id = AuthController.resolveUserId(session);
        if (id == null) return null;
        return userService.findById(id).orElse(null);
    }
}
