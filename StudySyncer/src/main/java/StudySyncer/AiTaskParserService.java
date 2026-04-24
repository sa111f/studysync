package StudySyncer;

import StudySyncer.config.AnthropicConfig;
import StudySyncer.dto.ParsedTaskResult;
import StudySyncer.entity.Priority;
import StudySyncer.entity.TaskType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.net.SocketTimeoutException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Calls the Anthropic Messages API with tool use to extract structured
 * task fields from a student's natural-language description.
 *
 * Why RestTemplate (not WebClient)?
 *   - spring-boot-starter-web is already present and ships RestTemplate.
 *   - WebClient requires spring-webflux, which would be a new dependency.
 *   - The call is a single synchronous request per /api/ai/parse-task hit;
 *     no streaming, no back-pressure, no concurrency gain from WebClient.
 *
 * Why tool use (not free-form JSON)?
 *   - Forcing tool_choice = {"type":"tool","name":"create_task"} means
 *     the model MUST return structured input matching our schema.
 *   - Eliminates the "extract a JSON code block from a text response"
 *     parsing brittleness; Anthropic returns tool_use blocks directly.
 */
@Service
public class AiTaskParserService {

    private static final Logger log = LoggerFactory.getLogger(AiTaskParserService.class);

    /** Hard upper bound so a pasted essay can't run up a token bill. */
    static final int MAX_INPUT_LENGTH = 500;

    /** Anthropic API version header required on every /v1/messages call. */
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final AnthropicConfig config;
    private final RestTemplate    restTemplate;
    private final ObjectMapper    mapper;

    /**
     * Production constructor used by Spring. {@code @Autowired} is required
     * because the test-only overload below makes this a multi-constructor
     * class, which disables Spring 4.3+ single-ctor auto-detection.
     */
    @Autowired
    public AiTaskParserService(AnthropicConfig config) {
        this.config       = config;
        this.restTemplate = buildRestTemplate(config.getTimeoutSeconds());
        this.mapper       = new ObjectMapper();
    }

    /**
     * Ctor overload used by tests to inject a pre-wired RestTemplate
     * (for example one with MockRestServiceServer attached).
     */
    AiTaskParserService(AnthropicConfig config, RestTemplate restTemplate) {
        this.config       = config;
        this.restTemplate = restTemplate;
        this.mapper       = new ObjectMapper();
    }

    private static RestTemplate buildRestTemplate(int timeoutSeconds) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int timeoutMs = Math.max(1, timeoutSeconds) * 1000;
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        return new RestTemplate(factory);
    }

    // ── Public API ──────────────────────────────────────────────────

    /**
     * Parse the user's natural-language description into structured task
     * fields. Returns a {@link ParsedTaskResult} — never throws for the
     * expected failure modes (invalid input, missing key, timeout, bad
     * tool payload). All of those are surfaced as typed failure reasons.
     */
    public ParsedTaskResult parseTask(String userInput, ZoneId userTimezone) {

        // ── Local validation (cheap — skip the round-trip if the input
        // is obviously unusable). Blank, whitespace-only, or too long.
        if (userInput == null || userInput.isBlank()) {
            return ParsedTaskResult.failure(
                    ParsedTaskResult.FailureReason.INVALID_INPUT,
                    "Please enter a short description.");
        }
        String trimmed = userInput.trim();
        if (trimmed.length() > MAX_INPUT_LENGTH) {
            return ParsedTaskResult.failure(
                    ParsedTaskResult.FailureReason.INVALID_INPUT,
                    "Description is too long (max " + MAX_INPUT_LENGTH + " characters).");
        }

        // ── Key check: if ANTHROPIC_API_KEY is unset, short-circuit.
        // No outbound call, no mysterious 401 from Anthropic — the
        // controller maps this to 503.
        if (!config.isConfigured()) {
            return ParsedTaskResult.failure(
                    ParsedTaskResult.FailureReason.AI_UNAVAILABLE,
                    "AI task parsing is not configured on this server.");
        }

        ZoneId zone = userTimezone != null ? userTimezone : ZoneId.of("UTC");
        LocalDate today = LocalDate.now(zone);

        // ── Build the request envelope.
        Map<String, Object> body = buildRequestBody(trimmed, today, zone);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-api-key",         config.getKey());
        headers.set("anthropic-version", ANTHROPIC_VERSION);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        // ── Call Anthropic. Failure modes:
        //     ResourceAccessException with SocketTimeoutException → TIMEOUT
        //     Any other exception / non-2xx                       → AI_UNAVAILABLE
        //     Tool block missing / malformed                      → AI_AMBIGUOUS
        ResponseEntity<String> response;
        try {
            response = restTemplate.postForEntity(config.getUrl(), entity, String.class);
        } catch (ResourceAccessException rae) {
            // RestTemplate wraps SocketTimeoutException in ResourceAccessException.
            if (rae.getCause() instanceof SocketTimeoutException) {
                log.warn("[AI] Anthropic call timed out after {}s", config.getTimeoutSeconds());
                return ParsedTaskResult.failure(
                        ParsedTaskResult.FailureReason.TIMEOUT,
                        "AI took too long to respond.");
            }
            log.warn("[AI] Anthropic network error: {}", rae.getMessage());
            return ParsedTaskResult.failure(
                    ParsedTaskResult.FailureReason.AI_UNAVAILABLE,
                    "Could not reach the AI service.");
        } catch (RestClientResponseException ex) {
            // Non-2xx from Anthropic (e.g., 401 bad key, 429 provider rate limit,
            // 5xx outage). Log status + body but never leak to the client.
            log.warn("[AI] Anthropic returned HTTP {} — body (truncated): {}",
                    ex.getStatusCode().value(),
                    truncate(ex.getResponseBodyAsString(), 300));
            return ParsedTaskResult.failure(
                    ParsedTaskResult.FailureReason.AI_UNAVAILABLE,
                    "AI service error.");
        } catch (Exception e) {
            log.warn("[AI] Unexpected error calling Anthropic: {}", e.getMessage());
            return ParsedTaskResult.failure(
                    ParsedTaskResult.FailureReason.AI_UNAVAILABLE,
                    "Unexpected AI error.");
        }

        HttpStatusCode status = response.getStatusCode();
        if (!status.is2xxSuccessful() || response.getBody() == null) {
            log.warn("[AI] Anthropic returned non-2xx status {}", status);
            return ParsedTaskResult.failure(
                    ParsedTaskResult.FailureReason.AI_UNAVAILABLE,
                    "AI service returned an unexpected status.");
        }

        // ── Parse the tool_use block.
        return extractToolUse(response.getBody(), trimmed, today);
    }

    // ── Request builder ─────────────────────────────────────────────

    private Map<String, Object> buildRequestBody(String userInput, LocalDate today, ZoneId zone) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model",       config.getModel());
        body.put("max_tokens",  1024);
        body.put("temperature", 0);
        body.put("system",      systemPrompt(today, zone));
        body.put("tools",       List.of(toolSchema()));
        body.put("tool_choice", Map.of("type", "tool", "name", "create_task"));
        body.put("messages",    List.of(Map.of(
                "role",    "user",
                "content", userInput)));
        return body;
    }

    /**
     * Tool definition (Anthropic schema). Defined in code (not JSON-on-disk)
     * so a typo would fail compilation rather than at runtime.
     */
    private Map<String, Object> toolSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("title",    Map.of("type", "string",
                "description", "Short task title, 2-8 words, no dates."));
        props.put("dueDate",  Map.of("type", "string",
                "description", "ISO date YYYY-MM-DD."));
        props.put("course",   Map.of("type", "string",
                "description", "Course name or code if mentioned."));
        props.put("type",     Map.of("type", "string",
                "enum", List.of("ASSIGNMENT", "LAB", "HOMEWORK", "PROJECT", "READING", "OTHER")));
        props.put("priority", Map.of("type", "string",
                "enum", List.of("LOW", "MEDIUM", "HIGH")));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type",       "object");
        schema.put("properties", props);
        schema.put("required",   List.of("title", "dueDate", "type", "priority"));

        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name",         "create_task");
        tool.put("description",  "Extract structured task fields from a student's description.");
        tool.put("input_schema", schema);
        return tool;
    }

    /**
     * System prompt with injected date/day/timezone. Kept inline (not a
     * template file) because the rules above are tuned specifically for
     * this tool — colocating prevents drift between spec and implementation.
     */
    private String systemPrompt(LocalDate today, ZoneId zone) {
        String isoToday = today.format(DateTimeFormatter.ISO_LOCAL_DATE);
        String dayName  = today.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        String tzId     = zone.getId();

        return "You extract task information from short student-written text and call the create_task tool with structured fields.\n\n"
             + "Today's date is " + isoToday + " (" + dayName + "). The user's timezone is " + tzId + ".\n\n"
             + "Rules:\n"
             + "- Always call the create_task tool exactly once.\n"
             + "- title: concise, 2–8 words, capitalize like a task title. Strip dates and the word \"due\".\n"
             + "- dueDate: ISO format YYYY-MM-DD. Resolve relative references to the nearest FUTURE date unless the text clearly indicates past (e.g. \"overdue since Monday\").\n"
             + "  - \"Friday\" / \"this Friday\" → the coming Friday (if today is Friday, interpret as today unless context says otherwise).\n"
             + "  - \"next Monday\" → the Monday of next week (always 7+ days away, never this week).\n"
             + "  - \"in 3 days\" → today + 3.\n"
             + "  - Month + day with no year → nearest future occurrence.\n"
             + "- course: extract if mentioned (\"for Chem\", \"EECS 281\", \"my Math class\"). Preserve the course code/name as written. If not mentioned, omit.\n"
             + "- type: pick the best match from ASSIGNMENT, LAB, HOMEWORK, PROJECT, READING, OTHER based on keywords. Default OTHER.\n"
             + "- priority: only set to HIGH if the input uses words like \"urgent\", \"important\", \"ASAP\", \"critical\". Only set to LOW if the input says \"optional\", \"if you have time\", \"low priority\". Otherwise MEDIUM.\n\n"
             + "Never ask clarifying questions. Always call the tool with your best interpretation.";
    }

    // ── Response parser ─────────────────────────────────────────────

    /**
     * Walks the Messages API response, finds the tool_use block for
     * create_task, and validates the input object against our schema
     * + enum set. Returns AI_AMBIGUOUS for anything that doesn't cleanly
     * map so the controller returns 502.
     */
    private ParsedTaskResult extractToolUse(String responseBody, String originalInput, LocalDate today) {
        JsonNode root;
        try {
            root = mapper.readTree(responseBody);
        } catch (Exception e) {
            log.warn("[AI] Response was not valid JSON: {}", e.getMessage());
            return ParsedTaskResult.failure(
                    ParsedTaskResult.FailureReason.AI_AMBIGUOUS,
                    "AI response was not valid JSON.");
        }

        JsonNode contentArr = root.path("content");
        if (!contentArr.isArray() || contentArr.isEmpty()) {
            return ParsedTaskResult.failure(
                    ParsedTaskResult.FailureReason.AI_AMBIGUOUS,
                    "AI response had no content.");
        }

        // Find the first tool_use block named create_task. Anthropic may
        // interleave text blocks; we only care about the tool call.
        JsonNode toolInput = null;
        for (Iterator<JsonNode> it = contentArr.elements(); it.hasNext(); ) {
            JsonNode block = it.next();
            if ("tool_use".equals(block.path("type").asText())
                    && "create_task".equals(block.path("name").asText())) {
                toolInput = block.path("input");
                break;
            }
        }
        if (toolInput == null || toolInput.isMissingNode() || !toolInput.isObject()) {
            return ParsedTaskResult.failure(
                    ParsedTaskResult.FailureReason.AI_AMBIGUOUS,
                    "AI did not return a tool call.");
        }

        // ── Required fields.
        String title = toolInput.path("title").asText(null);
        if (title == null || title.isBlank()) {
            return ParsedTaskResult.failure(
                    ParsedTaskResult.FailureReason.AI_AMBIGUOUS,
                    "AI omitted the task title.");
        }
        title = title.trim();

        String dueStr = toolInput.path("dueDate").asText(null);
        LocalDate dueDate;
        try {
            dueDate = LocalDate.parse(dueStr, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (Exception e) {
            return ParsedTaskResult.failure(
                    ParsedTaskResult.FailureReason.AI_AMBIGUOUS,
                    "AI returned an unparseable date.");
        }

        // ── Enums: fall back to defaults rather than AI_AMBIGUOUS.
        // Users can still edit them in the confirmation form.
        TaskType type;
        try {
            type = TaskType.valueOf(toolInput.path("type").asText("OTHER"));
        } catch (IllegalArgumentException e) {
            type = TaskType.OTHER;
        }
        Priority priority;
        try {
            priority = Priority.valueOf(toolInput.path("priority").asText("MEDIUM"));
        } catch (IllegalArgumentException e) {
            priority = Priority.MEDIUM;
        }

        // Optional: course. Treat empty string as absent.
        String course = null;
        JsonNode courseNode = toolInput.path("course");
        if (!courseNode.isMissingNode() && !courseNode.isNull()) {
            String raw = courseNode.asText("").trim();
            if (!raw.isEmpty()) course = raw;
        }

        // ── Past-date coercion safety net.
        // If the model picked a date in the past AND the input used "due",
        // roll forward in 7-day increments until we're on or after today.
        // Handles cases like "due Friday" on a Saturday where the model
        // resolved to yesterday's Friday.
        dueDate = coerceToFutureIfDue(dueDate, originalInput, today);

        String human = dueDate.format(
                DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.ENGLISH));

        return ParsedTaskResult.success(title, dueDate, human, course, type, priority);
    }

    /**
     * If the model resolved a day-of-week reference to a past date while
     * the input says "due", bump it forward one week at a time until it's
     * today-or-later. Visible for tests.
     */
    static LocalDate coerceToFutureIfDue(LocalDate dueDate, String input, LocalDate today) {
        if (dueDate == null || input == null || today == null) return dueDate;
        if (!dueDate.isBefore(today)) return dueDate;
        String lower = input.toLowerCase(Locale.ENGLISH);
        if (!lower.contains("due")) return dueDate;

        // Cap the loop to avoid pathological cases (prompt injection saying
        // "due Friday" + year 1900). Max ~200 weeks covers any realistic drift.
        LocalDate rolled = dueDate;
        int safety = 0;
        while (rolled.isBefore(today) && safety++ < 200) {
            rolled = rolled.plusWeeks(1);
        }
        return rolled;
    }

    /** Null-safe truncate used when logging upstream error bodies. */
    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
