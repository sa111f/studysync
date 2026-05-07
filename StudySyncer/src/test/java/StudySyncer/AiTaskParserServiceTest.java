package StudySyncer;

import StudySyncer.config.OpenAIConfig;
import StudySyncer.dto.ParsedTaskResult;
import StudySyncer.entity.Priority;
import StudySyncer.entity.TaskType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/**
 * Unit tests for {@link AiTaskParserService}.
 *
 * Approach: construct the service with a RestTemplate bound to a
 * MockRestServiceServer so we can stub OpenAI responses without
 * opening real sockets. No @SpringBootTest needed — faster and isolated.
 *
 * Past-date coercion is also covered by a direct unit test of the
 * package-private static helper so we don't need a full round-trip.
 */
class AiTaskParserServiceTest {

    private static final String OPENAI_URL = "https://api.openai.com/v1/chat/completions";

    private OpenAIConfig          config;
    private RestTemplate          restTemplate;
    private MockRestServiceServer server;
    private AiTaskParserService   service;

    @BeforeEach
    void setUp() {
        config = new OpenAIConfig();
        config.setKey("test-key");
        config.setUrl(OPENAI_URL);
        config.setModel("gpt-4o-mini");
        config.setTimeoutSeconds(15);

        restTemplate = new RestTemplate();
        server       = MockRestServiceServer.createServer(restTemplate);
        service      = new AiTaskParserService(config, restTemplate);
    }

    // ── Local validation ──────────────────────────────────────────

    @Test
    void parseTask_emptyInput_returnsInvalidInput() {
        ParsedTaskResult r = service.parseTask("", ZoneId.of("America/Toronto"));
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.getFailureReason()).isEqualTo(ParsedTaskResult.FailureReason.INVALID_INPUT);
    }

    @Test
    void parseTask_whitespaceOnly_returnsInvalidInput() {
        ParsedTaskResult r = service.parseTask("   \n\t  ", ZoneId.of("America/Toronto"));
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.getFailureReason()).isEqualTo(ParsedTaskResult.FailureReason.INVALID_INPUT);
    }

    @Test
    void parseTask_inputOver500Chars_returnsInvalidInput() {
        String tooLong = "x".repeat(501);
        ParsedTaskResult r = service.parseTask(tooLong, ZoneId.of("America/Toronto"));
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.getFailureReason()).isEqualTo(ParsedTaskResult.FailureReason.INVALID_INPUT);
    }

    // ── Configuration gate ────────────────────────────────────────

    @Test
    void parseTask_blankApiKey_returnsAiUnavailable() {
        OpenAIConfig empty = new OpenAIConfig();
        empty.setKey("");
        empty.setUrl(OPENAI_URL);
        empty.setModel("gpt-4o-mini");
        empty.setTimeoutSeconds(15);
        AiTaskParserService s = new AiTaskParserService(empty, new RestTemplate());

        ParsedTaskResult r = s.parseTask("Read chapter 4", ZoneId.of("UTC"));
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.getFailureReason()).isEqualTo(ParsedTaskResult.FailureReason.AI_UNAVAILABLE);
    }

    // ── Happy path ────────────────────────────────────────────────

    @Test
    void parseTask_withValidResponse_returnsSuccessWithMappedFields() {
        String response = """
                {
                  "choices": [{
                    "message": {
                      "content": "{\\"title\\":\\"Lab 3\\",\\"dueDate\\":\\"2026-05-04\\",\\"course\\":\\"EECS\\",\\"type\\":\\"LAB\\",\\"priority\\":\\"MEDIUM\\"}"
                    }
                  }]
                }
                """;

        server.expect(requestTo(OPENAI_URL))
              .andExpect(method(HttpMethod.POST))
              .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-key"))
              .andExpect(header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
              .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        ParsedTaskResult r = service.parseTask(
                "Lab 3 due next Monday for EECS", ZoneId.of("America/Toronto"));

        assertThat(r.isSuccess()).isTrue();
        assertThat(r.getTitle()).isEqualTo("Lab 3");
        assertThat(r.getDueDate()).isEqualTo(LocalDate.of(2026, 5, 4));
        assertThat(r.getCourse()).isEqualTo("EECS");
        assertThat(r.getType()).isEqualTo(TaskType.LAB);
        assertThat(r.getPriority()).isEqualTo(Priority.MEDIUM);
        assertThat(r.getResolvedDateHuman()).contains("Monday").contains("May").contains("2026");
    }

    // ── Unparseable response ──────────────────────────────────────

    @Test
    void parseTask_contentNotJson_returnsAiAmbiguous() {
        String response = """
                {
                  "choices": [{
                    "message": { "content": "Sorry, I can't help with that." }
                  }]
                }
                """;
        server.expect(requestTo(OPENAI_URL))
              .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        ParsedTaskResult r = service.parseTask("Lab 3", ZoneId.of("UTC"));
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.getFailureReason()).isEqualTo(ParsedTaskResult.FailureReason.AI_AMBIGUOUS);
    }

    @Test
    void parseTask_contentJsonWithBadDate_returnsAiAmbiguous() {
        String response = """
                {
                  "choices": [{
                    "message": {
                      "content": "{\\"title\\":\\"Lab\\",\\"dueDate\\":\\"not-a-date\\",\\"type\\":\\"LAB\\",\\"priority\\":\\"MEDIUM\\"}"
                    }
                  }]
                }
                """;
        server.expect(requestTo(OPENAI_URL))
              .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        ParsedTaskResult r = service.parseTask("Lab due Monday", ZoneId.of("UTC"));
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.getFailureReason()).isEqualTo(ParsedTaskResult.FailureReason.AI_AMBIGUOUS);
    }

    @Test
    void parseTask_invalidJsonResponseBody_returnsAiAmbiguous() {
        server.expect(requestTo(OPENAI_URL))
              .andRespond(withSuccess("not even json", MediaType.APPLICATION_JSON));

        ParsedTaskResult r = service.parseTask("Lab", ZoneId.of("UTC"));
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.getFailureReason()).isEqualTo(ParsedTaskResult.FailureReason.AI_AMBIGUOUS);
    }

    // ── Upstream errors ───────────────────────────────────────────

    @Test
    void parseTask_serverError_returnsAiUnavailable() {
        server.expect(requestTo(OPENAI_URL))
              .andRespond(withServerError());

        ParsedTaskResult r = service.parseTask("Lab", ZoneId.of("UTC"));
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.getFailureReason()).isEqualTo(ParsedTaskResult.FailureReason.AI_UNAVAILABLE);
    }

    @Test
    void parseTask_unauthorized_returnsAiUnavailable() {
        server.expect(requestTo(OPENAI_URL))
              .andRespond(withUnauthorizedRequest());

        ParsedTaskResult r = service.parseTask("Lab", ZoneId.of("UTC"));
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.getFailureReason()).isEqualTo(ParsedTaskResult.FailureReason.AI_UNAVAILABLE);
    }

    // ── Past-date coercion (pure function — no round-trip) ────────

    @Test
    void coerceToFutureIfDue_bumpsPastDateWhenInputSaysDue() {
        LocalDate today = LocalDate.of(2026, 5, 15);  // a Friday
        LocalDate past  = LocalDate.of(2026, 5, 8);   // last Friday — a week before

        LocalDate bumped = AiTaskParserService.coerceToFutureIfDue(past,
                "Lab due Friday", today);

        assertThat(bumped).isEqualTo(LocalDate.of(2026, 5, 15));
    }

    @Test
    void coerceToFutureIfDue_leavesFutureDateUntouched() {
        LocalDate today  = LocalDate.of(2026, 5, 15);
        LocalDate future = LocalDate.of(2026, 5, 22);

        LocalDate r = AiTaskParserService.coerceToFutureIfDue(future,
                "Lab due next Friday", today);

        assertThat(r).isEqualTo(future);
    }

    @Test
    void coerceToFutureIfDue_leavesPastDateAloneWhenInputDoesNotSayDue() {
        LocalDate today = LocalDate.of(2026, 5, 15);
        LocalDate past  = LocalDate.of(2026, 5, 8);

        LocalDate r = AiTaskParserService.coerceToFutureIfDue(past,
                "Overdue since last Monday", today);

        assertThat(r).isEqualTo(past);
    }
}
