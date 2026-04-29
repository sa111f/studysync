package StudySyncer;

import StudySyncer.config.OpenAIConfig;
import StudySyncer.dto.ParsedSyllabusResult;
import StudySyncer.dto.SyllabusItem;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Batch-extract every dated deliverable (task or exam) from a syllabus
 * using the OpenAI Chat Completions API with JSON mode.
 *
 * The service doesn't write to the DB — it returns a ParsedSyllabusResult
 * for the frontend review step to confirm/edit before POSTing to /api/bulk/import.
 */
@Service
public class AiSyllabusParserService {

    private static final Logger log = LoggerFactory.getLogger(AiSyllabusParserService.class);

    /** Per spec: truncate input text at 40,000 chars; flag in the response. */
    static final int MAX_INPUT_CHARS = 40_000;

    /** Bigger output budget — a full semester can have 20+ items. */
    private static final int MAX_OUTPUT_TOKENS = 4096;

    private final OpenAIConfig config;
    private final RestTemplate restTemplate;
    private final ObjectMapper mapper;

    @Autowired
    public AiSyllabusParserService(OpenAIConfig config) {
        this.config       = config;
        this.restTemplate = buildRestTemplate(config.getTimeoutSeconds());
        this.mapper       = new ObjectMapper();
    }

    /** Test-only ctor — inject a pre-wired RestTemplate for MockRestServiceServer. */
    AiSyllabusParserService(OpenAIConfig config, RestTemplate restTemplate) {
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

    public ParsedSyllabusResult parseSyllabus(String syllabusText, ZoneId userTimezone) {
        if (syllabusText == null || syllabusText.isBlank()) {
            return ParsedSyllabusResult.failure(
                    ParsedSyllabusResult.FailureReason.INVALID_INPUT,
                    "Syllabus text was empty.");
        }
        if (!config.isConfigured()) {
            return ParsedSyllabusResult.failure(
                    ParsedSyllabusResult.FailureReason.AI_UNAVAILABLE,
                    "AI syllabus parsing is not configured on this server.");
        }

        ZoneId zone = userTimezone != null ? userTimezone : ZoneId.of("UTC");
        LocalDate today = LocalDate.now(zone);

        boolean truncated = false;
        String input = syllabusText;
        if (input.length() > MAX_INPUT_CHARS) {
            truncated = true;
            input = input.substring(0, MAX_INPUT_CHARS);
            log.info("[AI-SYLL] Truncated input from {} to {} chars", syllabusText.length(), MAX_INPUT_CHARS);
        }

        Map<String, Object> body = buildRequestBody(input, today, zone);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(config.getKey());

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        ResponseEntity<String> response;
        try {
            response = restTemplate.postForEntity(config.getUrl(), entity, String.class);
        } catch (ResourceAccessException rae) {
            if (rae.getCause() instanceof SocketTimeoutException) {
                log.warn("[AI-SYLL] OpenAI call timed out after {}s", config.getTimeoutSeconds());
                return ParsedSyllabusResult.failure(
                        ParsedSyllabusResult.FailureReason.TIMEOUT,
                        "AI took too long to respond.");
            }
            log.warn("[AI-SYLL] OpenAI network error: {}", rae.getMessage());
            return ParsedSyllabusResult.failure(
                    ParsedSyllabusResult.FailureReason.AI_UNAVAILABLE,
                    "Could not reach the AI service.");
        } catch (RestClientResponseException ex) {
            log.warn("[AI-SYLL] OpenAI returned HTTP {} — body: {}",
                    ex.getStatusCode().value(), truncate(ex.getResponseBodyAsString(), 300));
            return ParsedSyllabusResult.failure(
                    ParsedSyllabusResult.FailureReason.AI_UNAVAILABLE,
                    "AI service error.");
        } catch (Exception e) {
            log.warn("[AI-SYLL] Unexpected error calling OpenAI: {}", e.getMessage());
            return ParsedSyllabusResult.failure(
                    ParsedSyllabusResult.FailureReason.AI_UNAVAILABLE,
                    "Unexpected AI error.");
        }

        HttpStatusCode status = response.getStatusCode();
        if (!status.is2xxSuccessful() || response.getBody() == null) {
            log.warn("[AI-SYLL] OpenAI returned non-2xx status {}", status);
            return ParsedSyllabusResult.failure(
                    ParsedSyllabusResult.FailureReason.AI_UNAVAILABLE,
                    "AI service returned an unexpected status.");
        }

        return extractFromResponse(response.getBody(), truncated, zone);
    }

    // ── Request builder ─────────────────────────────────────────────

    private Map<String, Object> buildRequestBody(String syllabusText, LocalDate today, ZoneId zone) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model",           config.getModel());
        body.put("temperature",     0);
        body.put("response_format", Map.of("type", "json_object"));
        body.put("max_tokens",      MAX_OUTPUT_TOKENS);
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt(today, zone)),
                Map.of("role", "user",   "content", syllabusText)
        ));
        return body;
    }

    private String systemPrompt(LocalDate today, ZoneId zone) {
        String isoToday = today.format(DateTimeFormatter.ISO_LOCAL_DATE);
        return "You extract all assignments, labs, homeworks, projects, readings, and exams from a course syllabus.\n\n"
             + "Today's date is " + isoToday + ". The user's timezone is " + zone.getId() + ".\n\n"
             + "Return ONLY a valid JSON object with exactly these fields — no markdown, no explanation:\n"
             + "  courseCode  (string or null) Detected course code, e.g. \"EECS 281\". Null if not found.\n"
             + "  items       (array) Every dated deliverable found. Each item has:\n"
             + "    kind      (string) \"task\" or \"exam\"\n"
             + "    title     (string) Deliverable name\n"
             + "    For tasks:  dueDate (YYYY-MM-DD), taskType (ASSIGNMENT/LAB/HOMEWORK/PROJECT/READING/OTHER), course (string or null)\n"
             + "    For exams:  dateTime (ISO-8601 with tz offset, default 09:00 local if no time given), course (string or null), material (string or null)\n\n"
             + "Rules:\n"
             + "- Extract EVERY deliverable with a date. Don't summarize. Don't skip.\n"
             + "- Resolve relative dates to ISO dates using the course calendar. If the year isn't specified, use the current or next academic year based on today's date.\n"
             + "- Skip items without clear due dates.\n"
             + "- Apply the detected courseCode to all items that don't specify their own course.\n"
             + "- If a final exam has no specific date, skip it.\n\n"
             + "Items array can be empty if nothing is found.";
    }

    // ── Response parser ─────────────────────────────────────────────

    private ParsedSyllabusResult extractFromResponse(String responseBody, boolean truncated, ZoneId zone) {
        JsonNode root;
        try {
            root = mapper.readTree(responseBody);
        } catch (Exception e) {
            return ParsedSyllabusResult.failure(
                    ParsedSyllabusResult.FailureReason.AI_AMBIGUOUS,
                    "AI response was not valid JSON.");
        }

        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return ParsedSyllabusResult.failure(
                    ParsedSyllabusResult.FailureReason.AI_AMBIGUOUS,
                    "AI response had no choices.");
        }

        String content = choices.get(0).path("message").path("content").asText(null);
        if (content == null || content.isBlank()) {
            return ParsedSyllabusResult.failure(
                    ParsedSyllabusResult.FailureReason.AI_AMBIGUOUS,
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
            return ParsedSyllabusResult.failure(
                    ParsedSyllabusResult.FailureReason.AI_AMBIGUOUS,
                    "AI returned unparseable JSON.");
        }

        // courseCode — may be null per spec (no header detected).
        String courseCode = null;
        JsonNode ccNode = parsed.path("courseCode");
        if (!ccNode.isMissingNode() && !ccNode.isNull()) {
            String raw = ccNode.asText("").trim();
            if (!raw.isEmpty()) courseCode = raw;
        }

        JsonNode itemsArr = parsed.path("items");
        if (!itemsArr.isArray()) {
            return ParsedSyllabusResult.failure(
                    ParsedSyllabusResult.FailureReason.AI_AMBIGUOUS,
                    "AI did not return an items array.");
        }

        List<SyllabusItem> out = new ArrayList<>(itemsArr.size());
        for (JsonNode node : itemsArr) {
            SyllabusItem item = mapItem(node, courseCode, zone);
            if (item != null) out.add(item);
        }
        return ParsedSyllabusResult.success(courseCode, out, truncated);
    }

    /**
     * Convert one JSON item into a typed SyllabusItem. Returns null for items
     * that fail per-kind validation — skipping a bad item rather than failing
     * the whole batch matches the spec's "skip items without clear due dates" rule.
     */
    private SyllabusItem mapItem(JsonNode node, String fallbackCourseCode, ZoneId zone) {
        String kind  = node.path("kind").asText("").toLowerCase();
        String title = node.path("title").asText("").trim();
        if (title.isEmpty()) return null;

        String course   = optText(node, "course");
        if (course == null) course = fallbackCourseCode;
        String material = optText(node, "material");

        if ("task".equals(kind)) {
            String dueStr = node.path("dueDate").asText(null);
            if (dueStr == null || dueStr.isBlank()) return null;
            LocalDate due;
            try { due = LocalDate.parse(dueStr, DateTimeFormatter.ISO_LOCAL_DATE); }
            catch (Exception e) { return null; }

            TaskType tt;
            try { tt = TaskType.valueOf(node.path("taskType").asText("OTHER")); }
            catch (IllegalArgumentException e) { tt = TaskType.OTHER; }

            return SyllabusItem.task(title, due, tt, course, material);
        }

        if ("exam".equals(kind)) {
            String dtStr = node.path("dateTime").asText(null);
            if (dtStr == null || dtStr.isBlank()) return null;
            Instant instant;
            try {
                instant = OffsetDateTime.parse(dtStr).toInstant();
            } catch (Exception first) {
                try { instant = LocalDateTime.parse(dtStr).atZone(zone).toInstant(); }
                catch (Exception e) { return null; }
            }
            return SyllabusItem.exam(title, instant, course, material);
        }

        // Unknown kind — drop silently rather than fail the batch.
        return null;
    }

    private static String optText(JsonNode node, String field) {
        JsonNode n = node.path(field);
        if (n.isMissingNode() || n.isNull()) return null;
        String raw = n.asText("").trim();
        return raw.isEmpty() ? null : raw;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
