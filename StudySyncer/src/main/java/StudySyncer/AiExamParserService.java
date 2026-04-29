package StudySyncer;

import StudySyncer.config.OpenAIConfig;
import StudySyncer.dto.ParsedExamResult;
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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Calls the OpenAI Chat Completions API with JSON mode to extract structured
 * exam fields from a student's natural-language description.
 */
@Service
public class AiExamParserService {

    private static final Logger log = LoggerFactory.getLogger(AiExamParserService.class);

    static final int MAX_INPUT_LENGTH = 500;

    private final OpenAIConfig config;
    private final RestTemplate restTemplate;
    private final ObjectMapper mapper;

    @Autowired
    public AiExamParserService(OpenAIConfig config) {
        this.config       = config;
        this.restTemplate = buildRestTemplate(config.getTimeoutSeconds());
        this.mapper       = new ObjectMapper();
    }

    /** Test-only ctor — inject a pre-wired RestTemplate for MockRestServiceServer. */
    AiExamParserService(OpenAIConfig config, RestTemplate restTemplate) {
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
        headers.setBearerAuth(config.getKey());

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        ResponseEntity<String> response;
        try {
            response = restTemplate.postForEntity(config.getUrl(), entity, String.class);
        } catch (ResourceAccessException rae) {
            if (rae.getCause() instanceof SocketTimeoutException) {
                log.warn("[AI-EXAM] OpenAI call timed out after {}s", config.getTimeoutSeconds());
                return ParsedExamResult.failure(
                        ParsedExamResult.FailureReason.TIMEOUT,
                        "AI took too long to respond.");
            }
            log.warn("[AI-EXAM] OpenAI network error: {}", rae.getMessage());
            return ParsedExamResult.failure(
                    ParsedExamResult.FailureReason.AI_UNAVAILABLE,
                    "Could not reach the AI service.");
        } catch (RestClientResponseException ex) {
            log.warn("[AI-EXAM] OpenAI returned HTTP {} — body: {}",
                    ex.getStatusCode().value(), truncate(ex.getResponseBodyAsString(), 300));
            return ParsedExamResult.failure(
                    ParsedExamResult.FailureReason.AI_UNAVAILABLE, "AI service error.");
        } catch (Exception e) {
            log.warn("[AI-EXAM] Unexpected error calling OpenAI: {}", e.getMessage());
            return ParsedExamResult.failure(
                    ParsedExamResult.FailureReason.AI_UNAVAILABLE, "Unexpected AI error.");
        }

        HttpStatusCode status = response.getStatusCode();
        if (!status.is2xxSuccessful() || response.getBody() == null) {
            log.warn("[AI-EXAM] OpenAI returned non-2xx status {}", status);
            return ParsedExamResult.failure(
                    ParsedExamResult.FailureReason.AI_UNAVAILABLE,
                    "AI service returned an unexpected status.");
        }

        return extractFromResponse(response.getBody(), trimmed, today, zone);
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
        String time     = LocalTime.now(zone).withNano(0).format(DateTimeFormatter.ofPattern("HH:mm"));

        return "You extract exam information from student text.\n\n"
             + "Today's date is " + isoToday + " (" + dayName + "). Current time is " + time + ". The user's timezone is " + zone.getId() + ".\n\n"
             + "Return ONLY a valid JSON object with exactly these fields — no markdown, no explanation:\n"
             + "  title    (string) concise exam name, e.g. \"Midterm 1\", \"Final Exam\", \"Quiz 3\". Strip dates.\n"
             + "  dateTime (string) ISO-8601 with the user's timezone offset, e.g. \"2026-06-15T14:00:00-04:00\". If no time is specified, default to 09:00 local.\n"
             + "           Resolve relative dates: \"next Monday\" = Monday of next week (7+ days away); \"this Friday\" = coming Friday; \"in 3 days\" = today + 3.\n"
             + "           Month + day with no year → nearest future occurrence.\n"
             + "  course   (string or null) Course name/code if mentioned, otherwise null.\n"
             + "  material (string or null) Chapters, topics, or sections if mentioned, otherwise null.\n"
             + "  location (string or null) Room/building if mentioned, otherwise null.\n\n"
             + "Example input: \"Physics midterm next Friday at 2pm in Science Hall\"\n"
             + "Example output: {\"title\":\"Midterm Exam\",\"dateTime\":\"2026-05-01T14:00:00-04:00\",\"course\":\"Physics\",\"material\":null,\"location\":\"Science Hall\"}";
    }

    // ── Response parser ─────────────────────────────────────────────

    private ParsedExamResult extractFromResponse(String responseBody, String originalInput,
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

        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return ParsedExamResult.failure(
                    ParsedExamResult.FailureReason.AI_AMBIGUOUS,
                    "AI response had no choices.");
        }

        String content = choices.get(0).path("message").path("content").asText(null);
        if (content == null || content.isBlank()) {
            return ParsedExamResult.failure(
                    ParsedExamResult.FailureReason.AI_AMBIGUOUS,
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
            log.warn("[AI-EXAM] Could not parse JSON from content: {}", e.getMessage());
            return ParsedExamResult.failure(
                    ParsedExamResult.FailureReason.AI_AMBIGUOUS,
                    "AI returned unparseable JSON.");
        }

        // ── Required fields.
        String title = parsed.path("title").asText(null);
        if (title == null || title.isBlank()) {
            return ParsedExamResult.failure(
                    ParsedExamResult.FailureReason.AI_AMBIGUOUS,
                    "AI omitted the exam title.");
        }
        title = title.trim();

        String dtStr = parsed.path("dateTime").asText(null);
        Instant dateTime;
        try {
            dateTime = OffsetDateTime.parse(dtStr).toInstant();
        } catch (Exception first) {
            try {
                dateTime = LocalDateTime.parse(dtStr).atZone(zone).toInstant();
            } catch (Exception e2) {
                return ParsedExamResult.failure(
                        ParsedExamResult.FailureReason.AI_AMBIGUOUS,
                        "AI returned an unparseable datetime.");
            }
        }

        String course   = optionalText(parsed, "course");
        String material = optionalText(parsed, "material");
        String location = optionalText(parsed, "location");

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
            rolled = rolled.plus(Duration.ofDays(7));
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
