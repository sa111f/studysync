package StudySyncer;

import StudySyncer.config.OpenAIConfig;
import StudySyncer.dto.ParsedExamResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/**
 * Parser tests for the exam flow. Parallel structure to
 * {@link AiTaskParserServiceTest} — uses MockRestServiceServer so no
 * real HTTP fires.
 */
class AiExamParserServiceTest {

    private static final String OPENAI_URL = "https://api.openai.com/v1/chat/completions";

    private OpenAIConfig          config;
    private RestTemplate          restTemplate;
    private MockRestServiceServer server;
    private AiExamParserService   service;

    @BeforeEach
    void setUp() {
        config = new OpenAIConfig();
        config.setKey("test-key");
        config.setUrl(OPENAI_URL);
        config.setModel("gpt-4o-mini");
        config.setTimeoutSeconds(15);

        restTemplate = new RestTemplate();
        server       = MockRestServiceServer.createServer(restTemplate);
        service      = new AiExamParserService(config, restTemplate);
    }

    // ── Happy path: ISO offset datetime ──────────────────────────

    @Test
    void parseExam_validResponse_mapsAllFields() {
        String response = """
                {
                  "choices": [{
                    "message": {
                      "content": "{\\"title\\":\\"Chem Final\\",\\"dateTime\\":\\"2026-04-30T14:00:00-04:00\\",\\"course\\":\\"CHEM 101\\",\\"material\\":\\"Chapters 1-6\\",\\"location\\":\\"Dennis 109\\"}"
                    }
                  }]
                }
                """;
        server.expect(requestTo(OPENAI_URL))
              .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-key"))
              .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        ParsedExamResult r = service.parseExam(
                "Chem final April 30 at 2pm chapters 1-6 in Dennis 109",
                ZoneId.of("America/Toronto"));

        assertThat(r.isSuccess()).isTrue();
        assertThat(r.getTitle()).isEqualTo("Chem Final");
        assertThat(r.getCourse()).isEqualTo("CHEM 101");
        assertThat(r.getMaterial()).isEqualTo("Chapters 1-6");
        assertThat(r.getLocation()).isEqualTo("Dennis 109");
        assertThat(r.getDateTime()).isEqualTo(
                ZonedDateTime.of(2026, 4, 30, 14, 0, 0, 0, ZoneId.of("America/Toronto"))
                             .toInstant());
        assertThat(r.getResolvedDateHuman())
                .contains("April").contains("2026").contains("2:00");
    }

    // ── Bad datetime → AI_AMBIGUOUS ───────────────────────────────

    @Test
    void parseExam_unparseableDateTime_returnsAmbiguous() {
        String response = """
                {
                  "choices": [{
                    "message": {
                      "content": "{\\"title\\":\\"Midterm\\",\\"dateTime\\":\\"soonish\\"}"
                    }
                  }]
                }
                """;
        server.expect(requestTo(OPENAI_URL))
              .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        ParsedExamResult r = service.parseExam("Midterm soonish", ZoneId.of("UTC"));
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.getFailureReason()).isEqualTo(ParsedExamResult.FailureReason.AI_AMBIGUOUS);
    }

    // ── Empty input short-circuits ────────────────────────────────

    @Test
    void parseExam_emptyInput_returnsInvalidInput() {
        ParsedExamResult r = service.parseExam("   ", ZoneId.of("UTC"));
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.getFailureReason()).isEqualTo(ParsedExamResult.FailureReason.INVALID_INPUT);
    }

    // ── Blank API key → AI_UNAVAILABLE (no HTTP fired) ────────────

    @Test
    void parseExam_blankApiKey_returnsAiUnavailable() {
        OpenAIConfig blank = new OpenAIConfig();
        blank.setKey("");
        blank.setUrl(OPENAI_URL);
        blank.setModel("gpt-4o-mini");
        blank.setTimeoutSeconds(15);
        AiExamParserService s = new AiExamParserService(blank, new RestTemplate());

        ParsedExamResult r = s.parseExam("Final exam next Monday", ZoneId.of("UTC"));
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.getFailureReason()).isEqualTo(ParsedExamResult.FailureReason.AI_UNAVAILABLE);
    }

    // ── Past-date coercion with "next" ────────────────────────────

    @Test
    void coerceToFutureIfNext_bumpsPastInstantForwardByWeeks() {
        ZoneId zone = ZoneId.of("America/Toronto");
        LocalDate today = LocalDate.of(2026, 5, 15);   // a Friday
        // AI resolved "next Friday" to LAST Friday — 7 days ago.
        Instant past = LocalDateTime.of(2026, 5, 8, 14, 0)
                                    .atZone(zone).toInstant();

        Instant rolled = AiExamParserService.coerceToFutureIfNext(
                past, "Chem exam next Friday", today, zone);

        assertThat(rolled).isEqualTo(
                LocalDateTime.of(2026, 5, 15, 14, 0).atZone(zone).toInstant());
    }

    @Test
    void coerceToFutureIfNext_leavesFutureInstantAlone() {
        ZoneId zone = ZoneId.of("UTC");
        LocalDate today = LocalDate.of(2026, 5, 15);
        Instant future = LocalDateTime.of(2026, 5, 22, 9, 0).atZone(zone).toInstant();

        Instant r = AiExamParserService.coerceToFutureIfNext(
                future, "Final next Friday", today, zone);
        assertThat(r).isEqualTo(future);
    }

    @Test
    void coerceToFutureIfNext_doesNothingWhenInputHasNoFutureIntent() {
        ZoneId zone = ZoneId.of("UTC");
        LocalDate today = LocalDate.of(2026, 5, 15);
        Instant past = LocalDateTime.of(2026, 5, 8, 9, 0).atZone(zone).toInstant();

        Instant r = AiExamParserService.coerceToFutureIfNext(
                past, "Overdue since last Monday", today, zone);

        assertThat(r).isEqualTo(past);
    }
}
