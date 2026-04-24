package StudySyncer;

import StudySyncer.config.AnthropicConfig;
import StudySyncer.dto.ParsedSyllabusResult;
import StudySyncer.dto.SyllabusItem;
import StudySyncer.entity.TaskType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/**
 * Tests for the batch syllabus parser. Uses MockRestServiceServer so no
 * real Anthropic calls fire.
 */
class AiSyllabusParserServiceTest {

    private AnthropicConfig         config;
    private RestTemplate            restTemplate;
    private MockRestServiceServer   server;
    private AiSyllabusParserService service;

    @BeforeEach
    void setUp() {
        config = new AnthropicConfig();
        config.setKey("test-key");
        config.setUrl("https://api.anthropic.com/v1/messages");
        config.setModel("claude-haiku-4-5-20251001");
        config.setTimeoutSeconds(15);

        restTemplate = new RestTemplate();
        server       = MockRestServiceServer.createServer(restTemplate);
        service      = new AiSyllabusParserService(config, restTemplate);
    }

    // ── Happy path: mixed task + exam items ───────────────────

    @Test
    void parseSyllabus_validToolUse_mapsBothKinds() {
        String response = """
                {
                  "content": [{
                    "type": "tool_use",
                    "name": "extract_syllabus_items",
                    "input": {
                      "courseCode": "EECS 281",
                      "items": [
                        {"kind": "task", "title": "Homework 1",
                         "dueDate": "2026-05-12", "taskType": "HOMEWORK",
                         "course": "EECS 281"},
                        {"kind": "exam", "title": "Midterm 1",
                         "dateTime": "2026-06-15T09:00:00-04:00",
                         "material": "Chapters 1-5", "course": "EECS 281"},
                        {"kind": "task", "title": "Project 2",
                         "dueDate": "2026-07-01", "taskType": "PROJECT"}
                      ]
                    }
                  }]
                }
                """;
        server.expect(requestTo(config.getUrl()))
              .andExpect(header("x-api-key", "test-key"))
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

    // ── Empty items array is a successful (no-op) result ─────

    @Test
    void parseSyllabus_emptyItemsArray_returnsSuccessWithEmptyList() {
        String response = """
                {
                  "content": [{
                    "type": "tool_use",
                    "name": "extract_syllabus_items",
                    "input": { "courseCode": null, "items": [] }
                  }]
                }
                """;
        server.expect(requestTo(config.getUrl()))
              .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        ParsedSyllabusResult r = service.parseSyllabus("Course description only", ZoneId.of("UTC"));
        assertThat(r.isSuccess()).isTrue();
        assertThat(r.getItems()).isEmpty();
        assertThat(r.getCourseCode()).isNull();
    }

    // ── Truncation flag surfaces when input > 40k chars ───────

    @Test
    void parseSyllabus_over40kChars_truncatesAndFlagsResult() {
        // Build ~42k-char input. Mock server echoes back an empty items list
        // so we can inspect the `truncated` flag without asserting on items.
        String big = "Lorem ipsum dolor sit amet. ".repeat(1500);
        assertThat(big.length()).isGreaterThan(AiSyllabusParserService.MAX_INPUT_CHARS);

        String response = """
                {
                  "content": [{
                    "type": "tool_use",
                    "name": "extract_syllabus_items",
                    "input": { "items": [] }
                  }]
                }
                """;
        server.expect(requestTo(config.getUrl()))
              .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        ParsedSyllabusResult r = service.parseSyllabus(big, ZoneId.of("UTC"));
        assertThat(r.isSuccess()).isTrue();
        assertThat(r.isTruncated()).isTrue();
    }

    // ── Empty input bypasses the HTTP call ────────────────────

    @Test
    void parseSyllabus_emptyInput_returnsInvalidInput() {
        ParsedSyllabusResult r = service.parseSyllabus("   ", ZoneId.of("UTC"));
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.getFailureReason()).isEqualTo(
                ParsedSyllabusResult.FailureReason.INVALID_INPUT);
    }

    // ── Blank API key → AI_UNAVAILABLE ────────────────────────

    @Test
    void parseSyllabus_blankApiKey_returnsAiUnavailable() {
        AnthropicConfig empty = new AnthropicConfig();
        empty.setKey("");
        empty.setUrl(config.getUrl());
        empty.setModel(config.getModel());
        empty.setTimeoutSeconds(15);
        AiSyllabusParserService s = new AiSyllabusParserService(empty, new RestTemplate());

        ParsedSyllabusResult r = s.parseSyllabus("Some syllabus text", ZoneId.of("UTC"));
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.getFailureReason()).isEqualTo(
                ParsedSyllabusResult.FailureReason.AI_UNAVAILABLE);
    }

    // ── Missing tool_use block → AI_AMBIGUOUS ─────────────────

    @Test
    void parseSyllabus_onlyTextBlock_returnsAmbiguous() {
        String response = """
                { "content": [ {"type": "text", "text": "Sorry."} ] }
                """;
        server.expect(requestTo(config.getUrl()))
              .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        ParsedSyllabusResult r = service.parseSyllabus("Hello", ZoneId.of("UTC"));
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.getFailureReason()).isEqualTo(
                ParsedSyllabusResult.FailureReason.AI_AMBIGUOUS);
    }

    // ── Malformed item in the list is skipped, rest succeed ──

    @Test
    void parseSyllabus_oneBadItem_skippedButOthersKept() {
        String response = """
                {
                  "content": [{
                    "type": "tool_use",
                    "name": "extract_syllabus_items",
                    "input": {
                      "items": [
                        {"kind": "task", "title": "Valid task",
                         "dueDate": "2026-05-12", "taskType": "HOMEWORK"},
                        {"kind": "task", "title": "Bad date task",
                         "dueDate": "soon-ish", "taskType": "HOMEWORK"},
                        {"kind": "exam", "title": "Valid exam",
                         "dateTime": "2026-06-15T09:00:00-04:00"}
                      ]
                    }
                  }]
                }
                """;
        server.expect(requestTo(config.getUrl()))
              .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        ParsedSyllabusResult r = service.parseSyllabus("Syllabus", ZoneId.of("UTC"));
        assertThat(r.isSuccess()).isTrue();
        // Bad-date row is dropped silently — 3 in → 2 out.
        assertThat(r.getItems()).hasSize(2);
        assertThat(r.getItems()).extracting(SyllabusItem::getTitle)
                .containsExactly("Valid task", "Valid exam");
    }
}
