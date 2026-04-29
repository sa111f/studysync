package StudySyncer;

import StudySyncer.config.OpenAIConfig;
import StudySyncer.dto.ParsedSyllabusResult;
import StudySyncer.dto.SyllabusItem;
import StudySyncer.entity.TaskType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/**
 * Tests for the batch syllabus parser. Uses MockRestServiceServer so no
 * real OpenAI calls fire.
 */
class AiSyllabusParserServiceTest {

    private static final String OPENAI_URL = "https://api.openai.com/v1/chat/completions";

    private OpenAIConfig          config;
    private RestTemplate          restTemplate;
    private MockRestServiceServer server;
    private AiSyllabusParserService service;

    @BeforeEach
    void setUp() {
        config = new OpenAIConfig();
        config.setKey("test-key");
        config.setUrl(OPENAI_URL);
        config.setModel("gpt-4o-mini");
        config.setTimeoutSeconds(15);

        restTemplate = new RestTemplate();
        server       = MockRestServiceServer.createServer(restTemplate);
        service      = new AiSyllabusParserService(config, restTemplate);
    }

    // ── Happy path: mixed task + exam items ───────────────────────

    @Test
    void parseSyllabus_validResponse_mapsBothKinds() {
        String response = """
                {
                  "choices": [{
                    "message": {
                      "content": "{\\"courseCode\\":\\"EECS 281\\",\\"items\\":[{\\"kind\\":\\"task\\",\\"title\\":\\"Homework 1\\",\\"dueDate\\":\\"2026-05-12\\",\\"taskType\\":\\"HOMEWORK\\",\\"course\\":\\"EECS 281\\"},{\\"kind\\":\\"exam\\",\\"title\\":\\"Midterm 1\\",\\"dateTime\\":\\"2026-06-15T09:00:00-04:00\\",\\"material\\":\\"Chapters 1-5\\",\\"course\\":\\"EECS 281\\"},{\\"kind\\":\\"task\\",\\"title\\":\\"Project 2\\",\\"dueDate\\":\\"2026-07-01\\",\\"taskType\\":\\"PROJECT\\"}]}"
                    }
                  }]
                }
                """;
        server.expect(requestTo(OPENAI_URL))
              .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-key"))
              .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        ParsedSyllabusResult r = service.parseSyllabus(
                "EECS 281 syllabus …", ZoneId.of("America/Toronto"));

        assertThat(r.isSuccess()).isTrue();
        assertThat(r.getCourseCode()).isEqualTo("EECS 281");
        assertThat(r.isTruncated()).isFalse();
        assertThat(r.getItems()).hasSize(3);

        SyllabusItem task1 = r.getItems().get(0);
        assertThat(task1.getKind()).isEqualTo("task");
        assertThat(task1.getTitle()).isEqualTo("Homework 1");
        assertThat(task1.getTaskType()).isEqualTo(TaskType.HOMEWORK);
        assertThat(task1.getDueDate()).isNotNull();

        SyllabusItem exam = r.getItems().get(1);
        assertThat(exam.getKind()).isEqualTo("exam");
        assertThat(exam.getTitle()).isEqualTo("Midterm 1");
        assertThat(exam.getDateTime()).isNotNull();
        assertThat(exam.getMaterial()).isEqualTo("Chapters 1-5");

        // Third task fell back to the detected courseCode.
        assertThat(r.getItems().get(2).getCourse()).isEqualTo("EECS 281");
    }

    // ── Empty items array is a successful (no-op) result ──────────

    @Test
    void parseSyllabus_emptyItemsArray_returnsSuccessWithEmptyList() {
        String response = """
                {
                  "choices": [{
                    "message": {
                      "content": "{\\"courseCode\\":null,\\"items\\":[]}"
                    }
                  }]
                }
                """;
        server.expect(requestTo(OPENAI_URL))
              .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        ParsedSyllabusResult r = service.parseSyllabus("Course description only", ZoneId.of("UTC"));
        assertThat(r.isSuccess()).isTrue();
        assertThat(r.getItems()).isEmpty();
        assertThat(r.getCourseCode()).isNull();
    }

    // ── Truncation flag surfaces when input > 40k chars ───────────

    @Test
    void parseSyllabus_over40kChars_truncatesAndFlagsResult() {
        String big = "Lorem ipsum dolor sit amet. ".repeat(1500);
        assertThat(big.length()).isGreaterThan(AiSyllabusParserService.MAX_INPUT_CHARS);

        String response = """
                {
                  "choices": [{
                    "message": {
                      "content": "{\\"items\\":[]}"
                    }
                  }]
                }
                """;
        server.expect(requestTo(OPENAI_URL))
              .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        ParsedSyllabusResult r = service.parseSyllabus(big, ZoneId.of("UTC"));
        assertThat(r.isSuccess()).isTrue();
        assertThat(r.isTruncated()).isTrue();
    }

    // ── Empty input bypasses the HTTP call ────────────────────────

    @Test
    void parseSyllabus_emptyInput_returnsInvalidInput() {
        ParsedSyllabusResult r = service.parseSyllabus("   ", ZoneId.of("UTC"));
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.getFailureReason()).isEqualTo(
                ParsedSyllabusResult.FailureReason.INVALID_INPUT);
    }

    // ── Blank API key → AI_UNAVAILABLE ────────────────────────────

    @Test
    void parseSyllabus_blankApiKey_returnsAiUnavailable() {
        OpenAIConfig empty = new OpenAIConfig();
        empty.setKey("");
        empty.setUrl(OPENAI_URL);
        empty.setModel("gpt-4o-mini");
        empty.setTimeoutSeconds(15);
        AiSyllabusParserService s = new AiSyllabusParserService(empty, new RestTemplate());

        ParsedSyllabusResult r = s.parseSyllabus("Some syllabus text", ZoneId.of("UTC"));
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.getFailureReason()).isEqualTo(
                ParsedSyllabusResult.FailureReason.AI_UNAVAILABLE);
    }

    // ── Non-JSON content → AI_AMBIGUOUS ───────────────────────────

    @Test
    void parseSyllabus_contentNotJson_returnsAmbiguous() {
        String response = """
                {
                  "choices": [{
                    "message": { "content": "Sorry, I cannot help with that." }
                  }]
                }
                """;
        server.expect(requestTo(OPENAI_URL))
              .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        ParsedSyllabusResult r = service.parseSyllabus("Hello", ZoneId.of("UTC"));
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.getFailureReason()).isEqualTo(
                ParsedSyllabusResult.FailureReason.AI_AMBIGUOUS);
    }

    // ── Malformed item in the list is skipped, rest succeed ───────

    @Test
    void parseSyllabus_oneBadItem_skippedButOthersKept() {
        String response = """
                {
                  "choices": [{
                    "message": {
                      "content": "{\\"items\\":[{\\"kind\\":\\"task\\",\\"title\\":\\"Valid task\\",\\"dueDate\\":\\"2026-05-12\\",\\"taskType\\":\\"HOMEWORK\\"},{\\"kind\\":\\"task\\",\\"title\\":\\"Bad date task\\",\\"dueDate\\":\\"soon-ish\\",\\"taskType\\":\\"HOMEWORK\\"},{\\"kind\\":\\"exam\\",\\"title\\":\\"Valid exam\\",\\"dateTime\\":\\"2026-06-15T09:00:00-04:00\\"}]}"
                    }
                  }]
                }
                """;
        server.expect(requestTo(OPENAI_URL))
              .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        ParsedSyllabusResult r = service.parseSyllabus("Syllabus", ZoneId.of("UTC"));
        assertThat(r.isSuccess()).isTrue();
        // Bad-date row is dropped silently — 3 in → 2 out.
        assertThat(r.getItems()).hasSize(2);
        assertThat(r.getItems()).extracting(SyllabusItem::getTitle)
                .containsExactly("Valid task", "Valid exam");
    }
}
