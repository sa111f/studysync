package StudySyncer.config;

import StudySyncer.entity.User;
import StudySyncer.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Optional;

/**
 * Handles successful Google OAuth2 login.
 *
 * Finds or creates a User entity from the Google profile, then stores the
 * user's ID in the HttpSession under the same "userId" key that AuthController uses —
 * making OAuth2 sessions fully compatible with session-based auth everywhere else.
 */
@Component
public class OAuth2SuccessHandler implements AuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(OAuth2SuccessHandler.class);

    private final UserRepository userRepository;

    public OAuth2SuccessHandler(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest  request,
                                        HttpServletResponse response,
                                        Authentication      authentication) throws IOException {

        OAuth2User oauthUser = (OAuth2User) authentication.getPrincipal();
        String email = oauthUser.getAttribute("email");
        String name  = oauthUser.getAttribute("name");

        if (email == null) {
            log.warn("[OAUTH2] Google did not return an email address — redirecting with error");
            response.sendRedirect("/?oauth_error=no_email");
            return;
        }

        email = email.trim().toLowerCase();

        // ── Find or create the local User record ──────────────
        Optional<User> existing = userRepository.findByEmailIgnoreCase(email);
        User user;

        if (existing.isPresent()) {
            user = existing.get();
            log.info("[OAUTH2] Existing user linked via Google — id={} username='{}'",
                     user.getId(), user.getUsername());
        } else {
            user = new User();
            user.setEmail(email);
            user.setUsername(deriveUsername(name, email));
            user.setEmailVerified(true);
            user.setOauthProvider("google");
            // passwordHash intentionally null — OAuth-only account
            user = userRepository.save(user);
            log.info("[OAUTH2] New user created via Google — id={} username='{}'",
                     user.getId(), user.getUsername());
        }

        // ── Bridge to our custom session auth ─────────────────
        // Store userId under the same key AuthController uses so every other
        // controller (TrackerController, PlanSaveController, etc.) works unchanged.
        HttpSession session = request.getSession(true);
        session.setAttribute("userId", user.getId());

        response.sendRedirect("https://www.studysyncer.com/");
    }

    // ── Username derivation ───────────────────────────────────

    /**
     * Converts a Google display name or email local-part into a valid, unique username.
     * Username rules: 3–50 chars, letters / numbers / underscores only.
     */
    private String deriveUsername(String displayName, String email) {
        // Prefer display name, fall back to email local-part
        String raw = (displayName != null && !displayName.isBlank())
            ? displayName
            : email.split("@")[0];

        // Strip disallowed characters and lowercase
        String base = raw.replaceAll("[^a-zA-Z0-9_]", "").toLowerCase();
        if (base.length() < 3) base = "user" + base;
        if (base.length() > 40) base = base.substring(0, 40);

        // Append a numeric suffix until the name is unique
        String candidate = base;
        int suffix = 1;
        while (userRepository.existsByUsernameIgnoreCase(candidate)) {
            candidate = base + suffix++;
        }
        return candidate;
    }
}
