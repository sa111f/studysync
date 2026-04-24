package StudySyncer;

import StudySyncer.config.AnthropicConfig;
import StudySyncer.dto.ParsedSyllabusResult;
import StudySyncer.dto.SyllabusItem;
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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Batch-extract every dated deliverable (task or exam) from a syllabus.
 *
 * Same HTTP/tool-use plumbing as AiTaskParserService / AiExamParserService,
 * but:
 *   - max_tokens bumped to 4096 to fit a full semester of items
 *   - tool returns an array of items discriminated by kind
 *   - input is truncated server-side at {@link #MAX_INPUT_CHARS} so a
 *     monster OCR-scanned syllabus can't run up a token bill
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

    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final AnthropicConfig config;
    private final RestTemplate    restTemplate;
    private final ObjectMapper    mapper;

    public AiSyllabusParserService(AnthropicConfig config) {
        this.config       = config;
        this.restTemplate = buildRestTemplate(config.getTimeoutSeconds());
        this.mapper       = new ObjectMapper();
    }

    /** Test-only ctor — inject a pre-wired RestTemplate for MockRestServiceServer. */
    AiSyllabusParserService(AnthropicConfig config, RestTemplate restTemplate) {
        this.config       = config;
        this.restTemplate = restTemplate;
        this.mapper       = new ObjectMapper();
    }

    private static RestTemplate buildRestTemplate(int timeoutSeconds) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        // Syllabus extractions can take longer than a task parse — still honour the
        // configured timeout, but note that a 15s cap may fire on slow days. The
        // controller maps that to 504; users can retry.
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
        headers.set("x-api-key",         config.getKey());
        headers.set("anthropic-version", ANTHROPIC_VERSION);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        ResponseEntity<String> response;
        try {
            response = restTemplate.postForEntity(config.getUrl(), entity, String.class);
        } catch (ResourceAccessException rae) {
            if (rae.getCause() instanceof SocketTimeoutException) {
                log.warn("[AI-SYLL] Anthropic call timed out after {}s", config.getTimeoutSeconds());
                return ParsedSyllabusResult.failure(
                        ParsedSyllabusResult.FailureReason.TIMEOUT,
                        "AI took too long to respond.");
            }
            log.warn("[AI-SYLL] Anthropic network error: {}", rae.getMessage());
            return ParsedSyllabusResult.failure(
                    ParsedSyllabusResult.FailureReason.AI_UNAVAILABLE,
                    "Could not reach the AI service.");
        } catch (RestClientResponseException ex) {
            log.warn("[AI-SYLL] Anthropic returned HTTP {} — body: {}",
                    ex.getStatusCode().value(), truncate(ex.getResponseBodyAsString(), 300));
            return ParsedSyllabusResult.failure(
                    ParsedSyllabusResult.FailureReason.AI_UNAVAILABLE,
                    "AI service error.");
        } catch (Exception e) {
            log.warn("[AI-SYLL] Unexpected error calling Anthropic: {}", e.getMessage());
            return ParsedSyllabusResult.failure(
                    ParsedSyllabusResult.FailureReason.AI_UNAVAILABLE,
                    "Unexpected AI error.");
        }

        HttpStatusCode status = response.getStatusCode();
        if (!status.is2xxSuccessful() || response.getBody() == null) {
            log.warn("[AI-SYLL] Anthropic returned non-2xx status {}", status);
            return ParsedSyllabusResult.failure(
                    ParsedSyllabusResult.FailureReason.AI_UNAVAILABLE,
                    "AI service returned an unexpected status.");
        }

        return extractToolUse(response.getBody(), truncated, zone);
    }

    // ── Request builder ─────────────────────────────────────────────

    private Map<String, Object> buildRequestBody(String syllabusText, LocalDate today, ZoneId zone) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model",       config.getModel());
        body.put("max_tokens",  MAX_OUTPUT_TOKENS);
        body.put("temperature", 0);
        body.put("system",      systemPrompt(today, zone));
        body.put("tools",       List.of(toolSchema()));
        body.put("tool_choice", Map.of("type", "tool", "name", "extract_syllabus_items"));
        body.put("messages",    List.of(Map.of("role", "user", "content", syllabusText)));
        return body;
    }

    /**
     * Tool schema. The `items[]` array entries are typed but not discriminated
     * at the JSON-Schema level (Anthropic's tool-use schema support doesn't
     * include oneOf at the time of writing). Both task and exam properties
     * are listed; we enforce per-kind validation in extractToolUse().
     */
    private Map<String, Object> toolSchema() {
        // Item properties (shared)
        Map<String, Object> itemProps = new LinkedHashMap<>();
        itemProps.put("kind",     Map.of("type", "string", "enum", List.of("task", "exam")));
        itemProps.put("title",    Map.of("type", "string"));
        itemProps.put("dueDate",  Map.of("type", "string",
                "description", "For tasks: YYYY-MM-DD."));
        itemProps.put("dateTime", Map.of("type", "string",
                "description", "For exams: ISO-8601 datetime."));
        itemProps.put("taskType", Map.of("type", "string",
                "enum", List.of("ASSIGNMENT","LAB","HOMEWORK","PROJECT","READING","OTHER")));
        itemProps.put("material", Map.of("type", "string"));
        itemProps.put("course",   Map.of("type", "string"));

        Map<String, Object> itemSchema = new LinkedHashMap<>();
        itemSchema.put("type",       "object");
        itemSchema.put("properties", itemProps);
        itemSchema.put("required",   List.of("kind", "title"));

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("courseCode", Map.of("type", "string",
                "description", "Detected course code, e.g. EECS 281."));
        props.put("items", Map.of(
                "type",  "array",
                "items", itemSchema));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type",       "object");
        schema.put("properties", props);
        schema.put("required",   List.of("items"));

        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name",         "extract_syllabus_items");
        tool.put("description",  "Extract every dated deliverable (task or exam) from a course syllabus.");
        tool.put("input_schema", schema);
        return tool;
    }

    private String systemPrompt(LocalDate today, ZoneId zone) {
        String isoToday = today.format(DateTimeFormatter.ISO_LOCAL_DATE);
        return "You extract all assignments, labs, homeworks, projects, readings, and exams from a course syllabus.\n\n"
             + "Today's date is " + isoToday + ". The user's timezone is " + zone.getId() + ".\n\n"
             + "Call the extract_syllabus_items tool ONCE with a list of all items you find. For each item:\n"
             + "- kind: \"task\" or \"exam\"\n"
             + "- For tasks: title, dueDate (YYYY-MM-DD), course (course code from the syllabus header), taskType (ASSIGNMENT/LAB/HOMEWORK/PROJECT/READING/OTHER)\n"
             + "- For exams: title (e.g. \"Midterm 1\", \"Final\"), dateTime (ISO-8601 with tz offset, default 09:00 local if time not given), course, material (chapters or topics to study if listed)\n\n"
             + "Rules:\n"
             + "- Extract EVERY deliverable with a date. Don't summarize. Don't skip.\n"
             + "- Resolve relative dates (e.g. \"Week 4 Friday\") to ISO dates using the course calendar if visible in the syllabus. If the year isn't specified, use the current or next academic year based on today's date.\n"
             + "- Skip items without clear due dates.\n"
             + "- Course code: extract once from the syllabus header and apply to all items. If not found, leave course null.\n"
             + "- If the syllabus mentions a final exam without a specific date (e.g. \"during finals week\"), skip it rather than guess.\n\n"
             + "Always call the tool. Items list can be empty if nothing is found.";
    }

    // ── Response parser ─────────────────────────────────────────────

    private ParsedSyllabusResult extractToolUse(String responseBody, boolean truncated, ZoneId zone) {
        JsonNode root;
        try {
            root = mapper.readTree(responseBody);
        } catch (Exception e) {
            return ParsedSyllabusResult.failure(
                    ParsedSyllabusResult.FailureReason.AI_AMBIGUOUS,
                    "AI response was not valid JSON.");
        }

        JsonNode contentArr = root.path("content");
        if (!contentArr.isArray() || contentArr.isEmpty()) {
            return ParsedSyllabusResult.failure(
                    ParsedSyllabusResult.FailureReason.AI_AMBIGUOUS,
                    "AI response had no content.");
        }

        JsonNode toolInput = null;
        for (Iterator<JsonNode> it = contentArr.elements(); it.hasNext(); ) {
            JsonNode block = it.next();
            if ("tool_use".equals(block.path("type").asText())
                    && "extract_syllabus_items".equals(block.path("name").asText())) {
                toolInput = block.path("input");
                break;
            }
        }
        if (toolInput == null || toolInput.isMissingNode() || !toolInput.isObject()) {
            return ParsedSyllabusResult.failure(
                    ParsedSyllabusResult.FailureReason.AI_AMBIGUOUS,
                    "AI did not return a tool call.");
        }

        // courseCode — may be null per spec (no header detected).
        String courseCode = null;
        JsonNode ccNode = toolInput.path("courseCode");
        if (!ccNode.isMissingNode() && !ccNode.isNull()) {
            String raw = ccNode.asText("").trim();
            if (!raw.isEmpty()) courseCode = raw;
        }

        // items[] — validate + coerce per-kind.
        JsonNode itemsArr = toolInput.path("items");
        if (!itemsArr.isArray()) {
            // Empty is fine (no items detected); missing/non-array is a parse failure.
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
     * Convert one tool-use JSON item into a typed SyllabusItem. Returns null
     * for items that fail per-kind validation rather than failing the whole
     * batch — matches the spec's "skip items without clear due dates" rule
     * and means a partially-unparseable syllabus still gives the user most
     * of what the model found.
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

        // Unknown kind — drop it silently rather than blow up the batch.
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
