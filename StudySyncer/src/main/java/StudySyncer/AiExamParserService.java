package StudySyncer;

import StudySyncer.config.AnthropicConfig;
import StudySyncer.dto.ParsedExamResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Exam counterpart to {@link AiTaskParserService}. Shares the same
 * Anthropic client setup (RestTemplate + forced tool_use + claude-haiku-4-5)
 * and the same failure-reason enum shape so the controller can route
 * both to the same status-code matrix.
 *
 * Why a sibling service (not a method on AiTaskParserService)?
 *   - Different tool schema (create_exam vs create_task)
 *   - Different prompt + date-default semantics (09:00 local when time
 *     isn't specified; always returns an Instant, not a LocalDate)
 *   - Keeps each service under one screen; easier to evolve prompts
 *     independently when we tune the model's behaviour per intent.
 */
@Service
public class AiExamParserService {

    private static final Logger log = LoggerFactory.getLogger(AiExamParserService.class);

    static final int MAX_INPUT_LENGTH = 500;
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final AnthropicConfig config;
    private final RestTemplate    restTemplate;
    private final ObjectMapper    mapper;

    public AiExamParserService(AnthropicConfig config) {
        this.config       = config;
        this.restTemplate = buildRestTemplate(config.getTimeoutSeconds());
        this.mapper       = new ObjectMapper();
    }

    /** Test-only ctor — inject a pre-wired RestTemplate for MockRestServiceServer. */
    AiExamParserService(AnthropicConfig config, RestTemplate restTemplate) {
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

    public ParsedExamResult parseExam(String userInput, ZoneId userTimezone) {

        if (userInput == null || userInput.isBlank()) {
            return ParsedExamResult.failure(
                    ParsedExamResult.FailureReason.INVALID_INPUT,
                    "Please enter a short description.");
        }
        String trimmed = userInput.trim();
        if (trimmed.length() > MAX_INPUT_LENGTH) {
            return ParsedExamResult.failure(
                    ParsedExamResult.FailureReason.INVALID_INPUT,
                    "Description is too long (max " + MAX_INPUT_LENGTH + " characters).");
        }
        if (!config.isConfigured()) {
            return ParsedExamResult.failure(
                    ParsedExamResult.FailureReason.AI_UNAVAILABLE,
                    "AI exam parsing is not configured on this server.");
        }

        ZoneId zone = userTimezone != null ? userTimezone : ZoneId.of("UTC");
        LocalDate today = LocalDate.now(zone);

        Map<String, Object> body = buildRequestBody(trimmed, today, zone);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-api-key",         config.getKey());
        headers.set("anthropic-version", ANTHROPIC_VERSION);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        ResponseEntity<String> response;
        try {
            response = restTemplate.postForEntity(config.getUrl(), entity, String.class);
        } catch (ResourceAccessException rae) {
            if (rae.getCause() instanceof SocketTimeoutException) {
                log.warn("[AI-EXAM] Anthropic call timed out after {}s", config.getTimeoutSeconds());
                return ParsedExamResult.failure(
                        ParsedExamResult.FailureReason.TIMEOUT,
                        "AI took too long to respond.");
            }
            log.warn("[AI-EXAM] Anthropic network error: {}", rae.getMessage());
            return ParsedExamResult.failure(
                    ParsedExamResult.FailureReason.AI_UNAVAILABLE,
                    "Could not reach the AI service.");
        } catch (RestClientResponseException ex) {
            log.warn("[AI-EXAM] Anthropic returned HTTP {} — body: {}",
                    ex.getStatusCode().value(), truncate(ex.getResponseBodyAsString(), 300));
            return ParsedExamResult.failure(
                    ParsedExamResult.FailureReason.AI_UNAVAILABLE, "AI service error.");
        } catch (Exception e) {
            log.warn("[AI-EXAM] Unexpected error calling Anthropic: {}", e.getMessage());
            return ParsedExamResult.failure(
                    ParsedExamResult.FailureReason.AI_UNAVAILABLE, "Unexpected AI error.");
        }

        HttpStatusCode status = response.getStatusCode();
        if (!status.is2xxSuccessful() || response.getBody() == null) {
            log.warn("[AI-EXAM] Anthropic returned non-2xx status {}", status);
            return ParsedExamResult.failure(
                    ParsedExamResult.FailureReason.AI_UNAVAILABLE,
                    "AI service returned an unexpected status.");
        }

        return extractToolUse(response.getBody(), trimmed, today, zone);
    }

    // ── Request builder ─────────────────────────────────────────────

    private Map<String, Object> buildRequestBody(String userInput, LocalDate today, ZoneId zone) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model",       config.getModel());
        body.put("max_tokens",  1024);
        body.put("temperature", 0);
        body.put("system",      systemPrompt(today, zone));
        body.put("tools",       List.of(toolSchema()));
        body.put("tool_choice", Map.of("type", "tool", "name", "create_exam"));
        body.put("messages",    List.of(Map.of("role", "user", "content", userInput)));
        return body;
    }

    private Map<String, Object> toolSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("title",    Map.of("type", "string", "description", "Exam name without dates."));
        props.put("dateTime", Map.of("type", "string", "description", "ISO-8601 datetime with timezone offset."));
        props.put("course",   Map.of("type", "string"));
        props.put("material", Map.of("type", "string",
                "description", "Chapters, topics, or sections to study."));
        props.put("location", Map.of("type", "string"));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type",       "object");
        schema.put("properties", props);
        schema.put("required",   List.of("title", "dateTime"));

        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name",         "create_exam");
        tool.put("description",  "Extract structured exam fields from a student's description.");
        tool.put("input_schema", schema);
        return tool;
    }

    private String systemPrompt(LocalDate today, ZoneId zone) {
        String isoToday = today.format(DateTimeFormatter.ISO_LOCAL_DATE);
        String dayName  = today.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        String time     = LocalTime.now(zone).withNano(0).format(DateTimeFormatter.ofPattern("HH:mm"));

        return "You extract exam information from student text and call the create_exam tool with structured fields.\n\n"
             + "Today's date is " + isoToday + " (" + dayName + "). Current time is " + time + ". The user's timezone is " + zone.getId() + ".\n\n"
             + "Rules:\n"
             + "- Always call the create_exam tool exactly once.\n"
             + "- title: concise exam name, e.g. \"Midterm 1\", \"Final Exam\", \"Chem Quiz 3\". Strip dates.\n"
             + "- dateTime: ISO-8601 with the user's timezone offset, e.g. \"2026-06-15T14:00:00-04:00\". If no time is specified, default to 09:00 local.\n"
             + "- Resolve relative dates: \"next Monday\" = Monday of next week (7+ days away); \"this Friday\" = coming Friday; \"in 3 days\" = today + 3.\n"
             + "- Month + day with no year → nearest future occurrence.\n"
             + "- course: extract if mentioned. Omit if not.\n"
             + "- material: extract any chapters, topics, or sections mentioned (e.g. \"chapters 1–6\", \"trig + vectors\"). Omit if not specified.\n"
             + "- location: extract if mentioned (\"in Dennis 109\", \"online\"). Omit if not.\n\n"
             + "Never ask clarifying questions. Always call the tool.";
    }

    // ── Response parser ─────────────────────────────────────────────

    private ParsedExamResult extractToolUse(String responseBody, String originalInput,
                                            LocalDate today, ZoneId zone) {
        JsonNode root;
        try {
            root = mapper.readTree(responseBody);
        } catch (Exception e) {
            log.warn("[AI-EXAM] Response was not valid JSON: {}", e.getMessage());
            return ParsedExamResult.failure(
                    ParsedExamResult.FailureReason.AI_AMBIGUOUS,
                    "AI response was not valid JSON.");
        }

        JsonNode contentArr = root.path("content");
        if (!contentArr.isArray() || contentArr.isEmpty()) {
            return ParsedExamResult.failure(
                    ParsedExamResult.FailureReason.AI_AMBIGUOUS,
                    "AI response had no content.");
        }

        JsonNode toolInput = null;
        for (Iterator<JsonNode> it = contentArr.elements(); it.hasNext(); ) {
            JsonNode block = it.next();
            if ("tool_use".equals(block.path("type").asText())
                    && "create_exam".equals(block.path("name").asText())) {
                toolInput = block.path("input");
                break;
            }
        }
        if (toolInput == null || toolInput.isMissingNode() || !toolInput.isObject()) {
            return ParsedExamResult.failure(
                    ParsedExamResult.FailureReason.AI_AMBIGUOUS,
                    "AI did not return a tool call.");
        }

        // ── Required fields.
        String title = toolInput.path("title").asText(null);
        if (title == null || title.isBlank()) {
            return ParsedExamResult.failure(
                    ParsedExamResult.FailureReason.AI_AMBIGUOUS,
                    "AI omitted the exam title.");
        }
        title = title.trim();

        String dtStr = toolInput.path("dateTime").asText(null);
        Instant dateTime;
        try {
            // Accept the common ISO-8601 variants the model might emit:
            //   "2026-06-15T14:00:00-04:00"
            //   "2026-06-15T14:00:00Z"
            //   "2026-06-15T14:00-04:00"  (seconds omitted — OffsetDateTime handles this)
            dateTime = OffsetDateTime.parse(dtStr).toInstant();
        } catch (Exception first) {
            // Fallback: treat as a local datetime without zone and interpret
            // in the user's timezone (matches our "default 09:00 local" rule).
            try {
                dateTime = java.time.LocalDateTime.parse(dtStr).atZone(zone).toInstant();
            } catch (Exception e2) {
                return ParsedExamResult.failure(
                        ParsedExamResult.FailureReason.AI_AMBIGUOUS,
                        "AI returned an unparseable datetime.");
            }
        }

        String course   = optionalText(toolInput, "course");
        String material = optionalText(toolInput, "material");
        String location = optionalText(toolInput, "location");

        // ── Past-date coercion safety net (mirrors AiTaskParserService).
        // If the model picked a past instant AND the input uses future-
        // intent words like "next" or "in N days", roll forward one week
        // at a time until we're on or after today (at the local date
        // level, not the instant — time-of-day is preserved).
        dateTime = coerceToFutureIfNext(dateTime, originalInput, today, zone);

        String human = formatHuman(dateTime, zone);

        return ParsedExamResult.success(title, dateTime, human, course, material, location);
    }

    /**
     * Visible for tests. Rolls a past datetime forward by full weeks while
     * the text contains forward-looking language ("next", "in N days",
     * "upcoming"). Max 200 weeks to bound pathological loops.
     */
    static Instant coerceToFutureIfNext(Instant dateTime, String input, LocalDate today, ZoneId zone) {
        if (dateTime == null || input == null || today == null) return dateTime;
        LocalDate examDate = dateTime.atZone(zone).toLocalDate();
        if (!examDate.isBefore(today)) return dateTime;

        String lower = input.toLowerCase(Locale.ENGLISH);
        boolean futureIntent = lower.contains("next")
                            || lower.contains("upcoming")
                            || lower.matches(".*\\bin\\s+\\d+\\s+day.*");
        if (!futureIntent) return dateTime;

        Instant rolled = dateTime;
        int safety = 0;
        while (rolled.atZone(zone).toLocalDate().isBefore(today) && safety++ < 200) {
            rolled = rolled.plus(java.time.Duration.ofDays(7));
        }
        return rolled;
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode n = node.path(field);
        if (n.isMissingNode() || n.isNull()) return null;
        String raw = n.asText("").trim();
        return raw.isEmpty() ? null : raw;
    }

    private static String formatHuman(Instant dateTime, ZoneId zone) {
        // "Monday, April 27, 2026 at 10:00 AM"
        return DateTimeFormatter
                .ofPattern("EEEE, MMMM d, yyyy 'at' h:mm a", Locale.ENGLISH)
                .withZone(zone)
                .format(dateTime);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
