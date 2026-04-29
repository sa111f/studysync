package StudySyncer;

import StudySyncer.config.OpenAIConfig;
import StudySyncer.dto.ParsedTaskResult;
import StudySyncer.entity.Priority;
import StudySyncer.entity.TaskType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Calls the OpenAI Chat Completions API with JSON mode to extract structured
 * task fields from a student's natural-language description.
 */
@Service
public class AiTaskParserService {

    private static final Logger log = LoggerFactory.getLogger(AiTaskParserService.class);

    /** Hard upper bound so a pasted essay can't run up a token bill. */
    static final int MAX_INPUT_LENGTH = 500;

    private final OpenAIConfig config;
    private final RestTemplate restTemplate;
    private final ObjectMapper mapper;

    /**
     * Production constructor used by Spring. {@code @Autowired} is required
     * because the test-only overload below makes this a multi-constructor
     * class, which disables Spring 4.3+ single-ctor auto-detection.
     */
    @Autowired
    public AiTaskParserService(OpenAIConfig config) {
        this.config       = config;
        this.restTemplate = buildRestTemplate(config.getTimeoutSeconds());
        this.mapper       = new ObjectMapper();
    }

    /** Test-only ctor — inject a pre-wired RestTemplate for MockRestServiceServer. */
    AiTaskParserService(OpenAIConfig config, RestTemplate restTemplate) {
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

    public ParsedTaskResult parseTask(String userInput, ZoneId userTimezone) {

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

        if (!config.isConfigured()) {
            return ParsedTaskResult.failure(
                    ParsedTaskResult.FailureReason.AI_UNAVAILABLE,
                    "AI task parsing is not configured on this server.");
        }

        ZoneId zone = userTimezone != null ? userTimezone : ZoneId.of("UTC");
        LocalDate today = LocalDate.now(zone);

        Map<String, Object> body = buildRequestBody(trimmed, today, zone);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(config.getKey());

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        ResponseEntity<String> response;
        try {
            response = restTemplate.postForEntity(config.getUrl(), entity, String.class);
        } catch (ResourceAccessException rae) {
            if (rae.getCause() instanceof SocketTimeoutException) {
                log.warn("[AI] OpenAI call timed out after {}s", config.getTimeoutSeconds());
                return ParsedTaskResult.failure(
                        ParsedTaskResult.FailureReason.TIMEOUT,
                        "AI took too long to respond.");
            }
            log.warn("[AI] OpenAI network error: {}", rae.getMessage());
            return ParsedTaskResult.failure(
                    ParsedTaskResult.FailureReason.AI_UNAVAILABLE,
                    "Could not reach the AI service.");
        } catch (RestClientResponseException ex) {
            log.warn("[AI] OpenAI returned HTTP {} — body (truncated): {}",
                    ex.getStatusCode().value(),
                    truncate(ex.getResponseBodyAsString(), 300));
            return ParsedTaskResult.failure(
                    ParsedTaskResult.FailureReason.AI_UNAVAILABLE,
                    "AI service error.");
        } catch (Exception e) {
            log.warn("[AI] Unexpected error calling OpenAI: {}", e.getMessage());
            return ParsedTaskResult.failure(
                    ParsedTaskResult.FailureReason.AI_UNAVAILABLE,
                    "Unexpected AI error.");
        }

        HttpStatusCode status = response.getStatusCode();
        if (!status.is2xxSuccessful() || response.getBody() == null) {
            log.warn("[AI] OpenAI returned non-2xx status {}", status);
            return ParsedTaskResult.failure(
                    ParsedTaskResult.FailureReason.AI_UNAVAILABLE,
                    "AI service returned an unexpected status.");
        }

        return extractFromResponse(response.getBody(), trimmed, today);
    }

    // ── Request builder ─────────────────────────────────────────────

    private Map<String, Object> buildRequestBody(String userInput, LocalDate today, ZoneId zone) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model",           config.getModel());
        body.put("temperature",     0);
        body.put("response_format", Map.of("type", "json_object"));
        body.put("max_tokens",      1024);
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt(today, zone)),
                Map.of("role", "user",   "content", userInput)
        ));
        return body;
    }

    private String systemPrompt(LocalDate today, ZoneId zone) {
        String isoToday = today.format(DateTimeFormatter.ISO_LOCAL_DATE);
        String dayName  = today.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        String tzId     = zone.getId();

        return "You extract task information from short student-written text.\n\n"
             + "Today's date is " + isoToday + " (" + dayName + "). The user's timezone is " + tzId + ".\n\n"
             + "Return ONLY a valid JSON object with exactly these fields — no markdown, no explanation:\n"
             + "  title    (string) concise, 2–8 words, capitalize like a task title. Strip dates and the word \"due\".\n"
             + "  dueDate  (string) ISO format YYYY-MM-DD. Resolve relative references to the nearest FUTURE date unless the text clearly indicates past.\n"
             + "           \"Friday\" / \"this Friday\" → the coming Friday. \"next Monday\" → Monday of next week (7+ days away, never this week).\n"
             + "           \"in 3 days\" → today + 3. Month + day with no year → nearest future occurrence.\n"
             + "  course   (string or null) Course name/code if mentioned, otherwise null.\n"
             + "  type     (string) One of: ASSIGNMENT, LAB, HOMEWORK, PROJECT, READING, OTHER — pick the best match. Default OTHER.\n"
             + "  priority (string) HIGH if input uses \"urgent\"/\"important\"/\"ASAP\"/\"critical\"; LOW if \"optional\"/\"if you have time\"/\"low priority\"; else MEDIUM.\n\n"
             + "Example input: \"Lab 3 due next Monday for EECS\"\n"
             + "Example output: {\"title\":\"Lab 3\",\"dueDate\":\"2026-05-04\",\"course\":\"EECS\",\"type\":\"LAB\",\"priority\":\"MEDIUM\"}";
    }

    // ── Response parser ─────────────────────────────────────────────

    private ParsedTaskResult extractFromResponse(String responseBody, String originalInput, LocalDate today) {
        JsonNode root;
        try {
            root = mapper.readTree(responseBody);
        } catch (Exception e) {
            log.warn("[AI] Response was not valid JSON: {}", e.getMessage());
            return ParsedTaskResult.failure(
                    ParsedTaskResult.FailureReason.AI_AMBIGUOUS,
                    "AI response was not valid JSON.");
        }

        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return ParsedTaskResult.failure(
                    ParsedTaskResult.FailureReason.AI_AMBIGUOUS,
                    "AI response had no choices.");
        }

        String content = choices.get(0).path("message").path("content").asText(null);
        if (content == null || content.isBlank()) {
            return ParsedTaskResult.failure(
                    ParsedTaskResult.FailureReason.AI_AMBIGUOUS,
                    "AI response content was empty.");
        }
        content = content.trim();
        if (content.startsWith("```")) {
            content = content.replaceAll("(?s)^```[a-z]*\\n?", "").replaceAll("```$", "").trim();
        }

        JsonNode parsed;
        try {
            parsed = mapper.readTree(content);
        } catch (Exception e) {
            log.warn("[AI] Could not parse JSON from content: {}", e.getMessage());
            return ParsedTaskResult.failure(
                    ParsedTaskResult.FailureReason.AI_AMBIGUOUS,
                    "AI returned unparseable JSON.");
        }

        // ── Required fields.
        String title = parsed.path("title").asText(null);
        if (title == null || title.isBlank()) {
            return ParsedTaskResult.failure(
                    ParsedTaskResult.FailureReason.AI_AMBIGUOUS,
                    "AI omitted the task title.");
        }
        title = title.trim();

        String dueStr = parsed.path("dueDate").asText(null);
        LocalDate dueDate;
        try {
            dueDate = LocalDate.parse(dueStr, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (Exception e) {
            return ParsedTaskResult.failure(
                    ParsedTaskResult.FailureReason.AI_AMBIGUOUS,
                    "AI returned an unparseable date.");
        }

        // ── Enums: fall back to defaults rather than AI_AMBIGUOUS.
        TaskType type;
        try {
            type = TaskType.valueOf(parsed.path("type").asText("OTHER"));
        } catch (IllegalArgumentException e) {
            type = TaskType.OTHER;
        }
        Priority priority;
        try {
            priority = Priority.valueOf(parsed.path("priority").asText("MEDIUM"));
        } catch (IllegalArgumentException e) {
            priority = Priority.MEDIUM;
        }

        // ── Optional: course.
        String course = null;
        JsonNode courseNode = parsed.path("course");
        if (!courseNode.isMissingNode() && !courseNode.isNull()) {
            String raw = courseNode.asText("").trim();
            if (!raw.isEmpty()) course = raw;
        }

        // ── Past-date coercion safety net.
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

        LocalDate rolled = dueDate;
        int safety = 0;
        while (rolled.isBefore(today) && safety++ < 200) {
            rolled = rolled.plusWeeks(1);
        }
        return rolled;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
