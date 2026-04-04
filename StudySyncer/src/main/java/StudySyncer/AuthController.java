package StudySyncer;

import StudySyncer.dto.LoginRequest;
import StudySyncer.dto.RegisterRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST endpoints for local (email + password) authentication.
 *
 * Session key "userId" (Long) is the single source of truth for who is logged in.
 * All other controllers call resolveUserId(session) to identify the current user.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    private static final String SESSION_USER_ID = "userId";

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    // ── Register ──────────────────────────────────────────

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest req) {
        log.info("[SIGNUP] POST /api/auth/register — username='{}'", req.getUsername());

        // Password-confirmation check before hitting the service
        String pw  = req.getPassword();
        String cpw = req.getConfirmPassword();
        if (pw != null && cpw != null && !pw.equals(cpw)) {
            log.debug("[SIGNUP] Password confirmation mismatch for username='{}'", req.getUsername());
            return ResponseEntity.badRequest()
                .body(Map.of("error", "Passwords do not match."));
        }

        try {
            userService.register(req.getUsername(), req.getEmail(), pw);
            log.info("[SIGNUP] Account created — username='{}'", req.getUsername());
            return ResponseEntity.ok(Map.of("message", "Account created! You can now log in."));

        } catch (IllegalArgumentException e) {
            // Bad input: missing field, too-short password, invalid email format, etc.
            log.debug("[SIGNUP] Validation error for username='{}': {}", req.getUsername(), e.getMessage());
            return ResponseEntity.badRequest()
                .body(Map.of("error", e.getMessage()));

        } catch (UserService.DuplicateFieldException e) {
            // 409 Conflict — username or email already taken
            log.info("[SIGNUP] Conflict on field '{}' for username='{}': {}",
                     e.getField(), req.getUsername(), e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", e.getMessage(), "field", e.getField()));

        } catch (DataIntegrityViolationException e) {
            // DB-level unique constraint hit (race condition past the existsBy checks, or
            // a stale schema with extra NOT-NULL columns).
            String msg = e.getMessage() != null ? e.getMessage().toUpperCase() : "";
            log.error("[SIGNUP] DB constraint violation for username='{}': {}",
                      req.getUsername(), e.getMessage(), e);
            if (msg.contains("USERNAME")) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Username already taken.", "field", "username"));
            }
            if (msg.contains("EMAIL")) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "An account with this email already exists.", "field", "email"));
            }
            // Schema mismatch — stale column in PostgreSQL users table.
            // Fix: inspect the 'users' table in pgAdmin. If extra NOT NULL columns
            // exist that are not in User.java, drop them and restart the app.
            log.error("[SIGNUP] DB constraint violation (not username/email) for username='{}'. " +
                      "Check the 'users' table schema in pgAdmin for unexpected NOT NULL columns.",
                      req.getUsername());
            return ResponseEntity.internalServerError()
                .body(Map.of("error", "Unable to create account right now. Please try again."));

        } catch (Exception e) {
            // Unexpected error (misconfiguration, etc.)
            log.error("[SIGNUP] Unexpected error for username='{}': {}",
                      req.getUsername(), e.getMessage(), e);
            return ResponseEntity.internalServerError()
                .body(Map.of("error", "Unable to create account right now. Please try again."));
        }
    }

    // ── Login ─────────────────────────────────────────────

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest req, HttpSession session) {
        String identifier = req.getIdentifier();
        String password   = req.getPassword();

        if (identifier == null || identifier.isBlank() || password == null || password.isBlank()) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", "Username/email and password are required."));
        }

        log.debug("[LOGIN] Attempt — identifier='{}'", identifier);
        UserService.AuthResult result = userService.authenticate(identifier, password);

        if (result.status() == UserService.AuthResult.AuthStatus.SUCCESS) {
            session.setAttribute(SESSION_USER_ID, result.user().getId());
            log.info("[LOGIN] Success — username='{}'", result.user().getUsername());
            return ResponseEntity.ok(Map.of("username", result.user().getUsername()));
        }

        log.debug("[LOGIN] Failed — identifier='{}'", identifier);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(Map.of("error", "Invalid username or password."));
    }

    // ── Logout ────────────────────────────────────────────

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpSession session) {
        session.invalidate();
        return ResponseEntity.ok(Map.of("message", "Logged out."));
    }

    // ── Current user ──────────────────────────────────────

    @GetMapping("/me")
    public ResponseEntity<?> me(HttpSession session) {
        Long userId = resolveUserId(session);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "Not logged in."));
        }
        return userService.findById(userId)
            .<ResponseEntity<?>>map(u -> ResponseEntity.ok(Map.of(
                "username", u.getUsername(),
                "email",    u.getEmail() != null ? u.getEmail() : ""
            )))
            .orElse(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "Session invalid.")));
    }

    // ── Shared utility ────────────────────────────────────

    public static Long resolveUserId(HttpSession session) {
        return (Long) session.getAttribute(SESSION_USER_ID);
    }
}
