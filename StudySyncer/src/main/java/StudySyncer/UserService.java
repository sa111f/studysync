package StudySyncer;

import StudySyncer.entity.User;
import StudySyncer.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Core user management: registration, authentication, lookup.
 *
 * Passwords are stored as BCrypt hashes — never plaintext.
 * Session management is handled by HttpSession in AuthController.
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    // ── Auth result ───────────────────────────────────────

    public record AuthResult(AuthStatus status, User user) {

        public enum AuthStatus { SUCCESS, INVALID_CREDENTIALS }

        public static AuthResult success(User u)  { return new AuthResult(AuthStatus.SUCCESS, u); }
        public static AuthResult invalidCreds()   { return new AuthResult(AuthStatus.INVALID_CREDENTIALS, null); }
    }

    // ── Custom exception for duplicate-key conflicts ───────

    /** Thrown when a chosen username or email is already registered. */
    public static class DuplicateFieldException extends RuntimeException {
        private final String field; // "username" or "email"

        public DuplicateFieldException(String field, String message) {
            super(message);
            this.field = field;
        }

        public String getField() { return field; }
    }

    // ── Dependencies ──────────────────────────────────────

    private final UserRepository        userRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    public UserService(UserRepository        userRepository,
                       BCryptPasswordEncoder passwordEncoder) {
        this.userRepository  = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    // ── Registration ──────────────────────────────────────

    /**
     * Creates a new account with a BCrypt-hashed password.
     *
     * @throws IllegalArgumentException  for invalid input (bad username format, short password, etc.)
     * @throws DuplicateFieldException   for duplicate username or email
     */
    @Transactional
    public User register(String username, String email, String password) {
        log.info("[SIGNUP] Request — username='{}' email='{}'", username, email);

        username = username == null ? null : username.trim();
        email    = email    == null ? null : email.trim().toLowerCase();

        // ── Input validation ─────────────────────────────
        if (username == null || username.isBlank())
            throw new IllegalArgumentException("Username is required.");
        if (username.length() < 3)
            throw new IllegalArgumentException("Username must be at least 3 characters.");
        if (!username.matches("[a-zA-Z0-9_]+"))
            throw new IllegalArgumentException("Username can only contain letters, numbers, and underscores.");
        if (username.length() > 50)
            throw new IllegalArgumentException("Username must be 50 characters or fewer.");

        if (email == null || email.isBlank())
            throw new IllegalArgumentException("Email is required.");
        if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))
            throw new IllegalArgumentException("A valid email address is required.");

        if (password == null || password.isBlank())
            throw new IllegalArgumentException("Password is required.");
        if (password.length() < 8)
            throw new IllegalArgumentException("Password must be at least 8 characters.");

        log.debug("[SIGNUP] Validation passed — checking uniqueness for username='{}' email='{}'",
                  username, email);

        // ── Uniqueness checks ────────────────────────────
        if (userRepository.existsByUsername(username)) {
            log.info("[SIGNUP] Rejected — username '{}' already taken", username);
            throw new DuplicateFieldException("username", "Username already taken.");
        }
        if (userRepository.existsByEmailIgnoreCase(email)) {
            log.info("[SIGNUP] Rejected — email '{}' already registered", email);
            throw new DuplicateFieldException("email", "An account with this email already exists.");
        }

        // ── Persist ──────────────────────────────────────
        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setEmailVerified(true); // no email verification step yet — account active immediately

        try {
            User saved = userRepository.save(user);
            log.info("[SIGNUP] Success — created user id={} username='{}'", saved.getId(), username);
            return saved;
        } catch (Exception e) {
            // Catch any DB-level constraint violation that slipped past the checks above
            // (e.g. race condition between existsBy check and save)
            log.error("[SIGNUP] DB save failed for username='{}': {}", username, e.getMessage());
            throw e; // let AuthController handle it
        }
    }

    // ── Authentication ────────────────────────────────────

    /**
     * Authenticates by username OR email + password.
     * Returns an AuthResult — never throws on bad credentials.
     */
    @Transactional(readOnly = true)
    public AuthResult authenticate(String identifier, String password) {
        if (identifier == null || password == null) return AuthResult.invalidCreds();

        identifier = identifier.trim();
        log.debug("[LOGIN] Attempt — identifier='{}'", identifier);

        Optional<User> found = identifier.contains("@")
            ? userRepository.findByEmailIgnoreCase(identifier)
            : userRepository.findByUsername(identifier);

        if (found.isEmpty()) {
            log.debug("[LOGIN] No account found for identifier='{}'", identifier);
            return AuthResult.invalidCreds();
        }

        User user = found.get();
        if (user.getPasswordHash() == null) {
            log.debug("[LOGIN] Account '{}' has no password (OAuth-only)", identifier);
            return AuthResult.invalidCreds();
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            log.debug("[LOGIN] Wrong password for identifier='{}'", identifier);
            return AuthResult.invalidCreds();
        }

        log.info("[LOGIN] Success — username='{}'", user.getUsername());
        return AuthResult.success(user);
    }

    // ── Lookup ────────────────────────────────────────────

    public Optional<User> findById(Long id) {
        return userRepository.findById(id);
    }
}
