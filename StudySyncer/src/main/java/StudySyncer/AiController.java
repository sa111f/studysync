package StudySyncer;

import StudySyncer.dto.ParsedTaskResult;
import StudySyncer.entity.User;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI-backed endpoints under /api/ai/*. Isolated from TaskController so
 * rate-limiting, feature flags, and future routes (e.g. AI plan
 * generation) can be scoped to just this path prefix.
 */
@RestController
@RequestMapping("/api/ai")
public class AiController {

    private static final Logger log = LoggerFactory.getLogger(AiController.class);

    private final AiTaskParserService parserService;
    private final AiRateLimiter       rateLimiter;
    private final UserService         userService;

    public AiController(AiTaskParserService parserService,
                        AiRateLimiter rateLimiter,
                        UserService userService) {
        this.parserService = parserService;
        this.rateLimiter   = rateLimiter;
        this.userService   = userService;
    }

    /**
     * POST /api/ai/parse-task
     *   Body: {"input": "Lab 3 due next Monday for EECS", "timezone": "America/Toronto"}
     *
     * Status code matrix:
     *   200 parsed            → {"parsed": {...}, "rawInput": "..."}
     *   400 invalid input     → {"error": "..."}
     *   401 not logged in     → {"error": "Not logged in."}
     *   429 rate limited      → {"error": "...", "retryAfterSeconds": N}
     *   502 AI unparseable    → {"error": "AI returned unparseable output."}
     *   503 AI not configured → {"error": "AI task parsing is not configured on this server."}
     *   504 AI timed out      → {"error": "AI took too long to respond."}
     */
    @PostMapping("/parse-task")
    public ResponseEntity<?> parseTask(@RequestBody ParseTaskRequest req, HttpSession session) {
        User user = resolveUser(session);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Not logged in."));
        }

        String input = req != null ? req.getInput() : null;
        if (input == null || input.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Please enter a short description."));
        }
        if (input.length() > AiTaskParserService.MAX_INPUT_LENGTH) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error",
                            "Description is too long (max "
                                    + AiTaskParserService.MAX_INPUT_LENGTH + " characters)."));
        }

        // ── Rate limit gate (checked BEFORE the AI call to avoid burning
        // tokens for requests that would be rejected anyway).
        AiRateLimiter.Decision gate = rateLimiter.tryAcquire(user.getId());
        if (!gate.isAllowed()) {
            log.info("[AI] Rate-limited userId={} — retryAfter={}s",
                    user.getId(), gate.getRetryAfterSeconds());
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(gate.getRetryAfterSeconds()));
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .headers(headers)
                    .body(Map.of(
                            "error", "Too many AI requests — try again in an hour.",
                            "retryAfterSeconds", gate.getRetryAfterSeconds()));
        }

        // ── Timezone: trust the browser-reported IANA string; fall back to UTC.
        ZoneId zone;
        try {
            zone = (req.getTimezone() != null && !req.getTimezone().isBlank())
                    ? ZoneId.of(req.getTimezone())
                    : ZoneId.of("UTC");
        } catch (Exception e) {
            zone = ZoneId.of("UTC");
        }

        ParsedTaskResult result = parserService.parseTask(input, zone);

        if (result.isSuccess()) {
            Map<String, Object> parsed = new LinkedHashMap<>();
            parsed.put("title",              result.getTitle());
            parsed.put("dueDate",            result.getDueDate().toString());
            parsed.put("resolvedDateHuman",  result.getResolvedDateHuman());
            parsed.put("course",             result.getCourse());   // nullable
            parsed.put("type",               result.getType().name());
            parsed.put("priority",           result.getPriority().name());

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("parsed",   parsed);
            body.put("rawInput", input);
            return ResponseEntity.ok(body);
        }

        // Map failure reasons to HTTP per spec 5.4.
        switch (result.getFailureReason()) {
            case INVALID_INPUT:
                // Shouldn't reach here (we pre-validated), but keep a mapping
                // in case the service adds stricter internal checks later.
                return ResponseEntity.badRequest()
                        .body(Map.of("error", hintOr(result, "Please enter a short description.")));
            case AI_UNAVAILABLE:
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(Map.of("error", hintOr(result, "AI is currently unavailable.")));
            case TIMEOUT:
                return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                        .body(Map.of("error", hintOr(result, "AI took too long to respond.")));
            case AI_AMBIGUOUS:
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                        .body(Map.of("error", hintOr(result, "AI returned unparseable output.")));
            default:
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(Map.of("error", "Unexpected AI failure."));
        }
    }

    private static String hintOr(ParsedTaskResult r, String fallback) {
        return r.getHint() != null && !r.getHint().isBlank() ? r.getHint() : fallback;
    }

    private User resolveUser(HttpSession session) {
        Long id = AuthController.resolveUserId(session);
        if (id == null) return null;
        return userService.findById(id).orElse(null);
    }

    // ── Nested request DTO ──────────────────────────────────────────
    // Trivial enough to keep here rather than spawn another dto/ file.
    public static class ParseTaskRequest {
        private String input;
        private String timezone;
        public String getInput()              { return input; }
        public String getTimezone()           { return timezone; }
        public void setInput(String input)    { this.input = input; }
        public void setTimezone(String tz)    { this.timezone = tz; }
    }
}
