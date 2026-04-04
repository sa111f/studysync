package StudySyncer.dev;

import StudySyncer.entity.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * DEV ONLY — loaded only when spring.profiles.active=dev.
 *
 * Endpoints:
 *   GET  /dev/admin                — browser admin UI
 *   GET  /dev/users                — JSON: list all users
 *   DELETE /dev/users/{username}   — JSON: delete one user
 *   POST /dev/users/{username}/delete — HTML-form-compatible delete
 *   POST /dev/users/reset          — wipe every user + their data
 */
@Controller
@Profile("dev")
@RequestMapping("/dev")
public class DevController {

    private final DevToolsService devToolsService;

    @Value("${app.dev.reset-on-start:false}")
    private boolean resetOnStart;

    public DevController(DevToolsService devToolsService) {
        this.devToolsService = devToolsService;
    }

    // ── Startup reset (optional) ──────────────────────────

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (!resetOnStart) return;
        int deleted = devToolsService.resetAllUsers();
        System.out.printf(
            "%n[DEV] reset-on-start: wiped %d user(s) at %s%n%n",
            deleted, LocalDateTime.now());
    }

    // ── Admin UI (browser) ────────────────────────────────

    @GetMapping("/admin")
    public String adminPage(Model model,
                            @RequestParam(required = false) String msg,
                            @RequestParam(required = false) String err) {
        List<User> users = devToolsService.findAllUsers();
        model.addAttribute("users",        users);
        model.addAttribute("userCount",    users.size());
        model.addAttribute("resetOnStart", resetOnStart);
        model.addAttribute("msg",          msg);
        model.addAttribute("err",          err);
        return "dev/admin";
    }

    // ── HTML-form-compatible deletes (used by admin page) ────

    /** Delete button in the users table (one row per user). */
    @PostMapping("/users/{username}/delete")
    public String deleteUserForm(@PathVariable String username,
                                 RedirectAttributes ra) {
        try {
            devToolsService.deleteUser(username);
            ra.addAttribute("msg", "Deleted user: " + username);
        } catch (NoSuchElementException e) {
            ra.addAttribute("err", e.getMessage());
        }
        return "redirect:/dev/admin";
    }

    /** "Delete by username" text-input form on the admin page. */
    @PostMapping("/users/delete-by-name")
    public String deleteUserByName(@RequestParam String username,
                                   RedirectAttributes ra) {
        if (username == null || username.isBlank()) {
            ra.addAttribute("err", "Username is required.");
            return "redirect:/dev/admin";
        }
        return deleteUserForm(username.trim(), ra);
    }

    // ── HTML-form reset (used by admin page) ──────────────

    @PostMapping("/users/reset")
    public String resetUsersForm(RedirectAttributes ra) {
        int count = devToolsService.resetAllUsers();
        ra.addAttribute("msg", "Reset complete — deleted " + count + " user(s).");
        return "redirect:/dev/admin";
    }

    // ── JSON API (curl / Postman / tests) ────────────────

    /** GET /dev/users → list all users as JSON */
    @GetMapping("/users")
    @ResponseBody
    public List<Map<String, Object>> listUsers() {
        return devToolsService.findAllUsers().stream()
            .map(this::toMap)
            .toList();
    }

    /** DELETE /dev/users/{username} → delete one user */
    @DeleteMapping("/users/{username}")
    @ResponseBody
    public ResponseEntity<Map<String, String>> deleteUser(@PathVariable String username) {
        try {
            devToolsService.deleteUser(username);
            return ResponseEntity.ok(Map.of("deleted", username));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }

    /** POST /dev/api/users/reset → wipe all users, returns JSON (for curl/Postman) */
    @PostMapping("/api/users/reset")
    @ResponseBody
    public Map<String, Object> resetUsersJson() {
        int count = devToolsService.resetAllUsers();
        return Map.of("deleted", count, "message", "All users wiped.");
    }

    // ── Helpers ───────────────────────────────────────────

    private Map<String, Object> toMap(User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",        u.getId());
        m.put("username",  u.getUsername());
        m.put("email",     u.getEmail() != null ? u.getEmail() : "");
        m.put("createdAt", u.getCreatedAt() != null ? u.getCreatedAt().toString() : "");
        return m;
    }
}
