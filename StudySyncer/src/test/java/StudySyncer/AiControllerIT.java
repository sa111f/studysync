package StudySyncer;

import StudySyncer.dto.ParsedTaskResult;
import StudySyncer.entity.Priority;
import StudySyncer.entity.TaskType;
import StudySyncer.entity.User;
import StudySyncer.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for AiController. The real AiTaskParserService is
 * replaced with a mock so no outbound HTTP fires during tests; we're
 * only verifying controller wiring + status mappings + rate limiting.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class AiControllerIT {

    @Autowired private MockMvc        mockMvc;
    @Autowired private UserRepository userRepo;
    @Autowired private AiRateLimiter  rateLimiter;

    @MockBean private AiTaskParserService parserService;

    private User            user;
    private MockHttpSession session;

    @BeforeEach
    void setUp() {
        rateLimiter.reset();
        userRepo.deleteAll();

        user = userRepo.save(buildUser("ai_it_user"));
        session = new MockHttpSession();
        session.setAttribute("userId", user.getId());
    }

    @AfterEach
    void tearDown() {
        rateLimiter.reset();
        userRepo.deleteAll();
    }

    // ── 401 when not authenticated ─────────────────────────────

    @Test
    void parseTask_noSession_returns401() throws Exception {
        mockMvc.perform(post("/api/ai/parse-task")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":\"Lab 3 due Friday\",\"timezone\":\"UTC\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").exists());
    }

    // ── 400 on empty input ─────────────────────────────────────

    @Test
    void parseTask_emptyInput_returns400() throws Exception {
        mockMvc.perform(post("/api/ai/parse-task")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":\"\",\"timezone\":\"UTC\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void parseTask_oversizedInput_returns400() throws Exception {
        String body = "{\"input\":\"" + "x".repeat(501) + "\",\"timezone\":\"UTC\"}";
        mockMvc.perform(post("/api/ai/parse-task")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    // ── 200 happy path ─────────────────────────────────────────

    @Test
    void parseTask_mockedSuccess_returns200WithParsedFields() throws Exception {
        ParsedTaskResult ok = ParsedTaskResult.success(
                "Lab 3",
                LocalDate.of(2026, 5, 4),
                "Monday, May 4, 2026",
                "EECS",
                TaskType.LAB,
                Priority.MEDIUM);
        when(parserService.parseTask(anyString(), any(ZoneId.class))).thenReturn(ok);

        mockMvc.perform(post("/api/ai/parse-task")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":\"Lab 3 due next Monday for EECS\",\"timezone\":\"America/Toronto\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parsed.title").value("Lab 3"))
                .andExpect(jsonPath("$.parsed.dueDate").value("2026-05-04"))
                .andExpect(jsonPath("$.parsed.resolvedDateHuman").value("Monday, May 4, 2026"))
                .andExpect(jsonPath("$.parsed.course").value("EECS"))
                .andExpect(jsonPath("$.parsed.type").value("LAB"))
                .andExpect(jsonPath("$.parsed.priority").value("MEDIUM"))
                .andExpect(jsonPath("$.rawInput").value("Lab 3 due next Monday for EECS"));
    }

    // ── 503 when service reports AI_UNAVAILABLE ────────────────

    @Test
    void parseTask_serviceReportsAiUnavailable_returns503() throws Exception {
        when(parserService.parseTask(anyString(), any(ZoneId.class)))
                .thenReturn(ParsedTaskResult.failure(
                        ParsedTaskResult.FailureReason.AI_UNAVAILABLE,
                        "AI task parsing is not configured on this server."));

        mockMvc.perform(post("/api/ai/parse-task")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":\"Lab 3 due Friday\",\"timezone\":\"UTC\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void parseTask_serviceReportsTimeout_returns504() throws Exception {
        when(parserService.parseTask(anyString(), any(ZoneId.class)))
                .thenReturn(ParsedTaskResult.failure(
                        ParsedTaskResult.FailureReason.TIMEOUT, "AI took too long to respond."));

        mockMvc.perform(post("/api/ai/parse-task")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":\"Lab\",\"timezone\":\"UTC\"}"))
                .andExpect(status().isGatewayTimeout());
    }

    @Test
    void parseTask_serviceReportsAmbiguous_returns502() throws Exception {
        when(parserService.parseTask(anyString(), any(ZoneId.class)))
                .thenReturn(ParsedTaskResult.failure(
                        ParsedTaskResult.FailureReason.AI_AMBIGUOUS, "AI returned something odd."));

        mockMvc.perform(post("/api/ai/parse-task")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":\"Lab\",\"timezone\":\"UTC\"}"))
                .andExpect(status().isBadGateway());
    }

    // ── 429 after hitting the rate limit ───────────────────────

    @Test
    void parseTask_rateLimitExceeded_returns429WithRetryAfter() throws Exception {
        ParsedTaskResult ok = ParsedTaskResult.success(
                "t", LocalDate.now(), "today", null, TaskType.OTHER, Priority.MEDIUM);
        when(parserService.parseTask(anyString(), any(ZoneId.class))).thenReturn(ok);

        // Burn all 20 allowed calls.
        for (int i = 0; i < AiRateLimiter.MAX_CALLS; i++) {
            mockMvc.perform(post("/api/ai/parse-task")
                            .session(session)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"input\":\"Lab " + i + "\",\"timezone\":\"UTC\"}"))
                    .andExpect(status().isOk());
        }

        // 21st call — rate limited.
        mockMvc.perform(post("/api/ai/parse-task")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":\"Lab overflow\",\"timezone\":\"UTC\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.error").exists())
                .andExpect(jsonPath("$.retryAfterSeconds").isNumber());
    }

    // ── Helper ─────────────────────────────────────────────────

    private User buildUser(String username) {
        User u = new User();
        u.setUsername(username);
        u.setEmail(username + "@test.example");
        u.setPasswordHash("$2a$10$placeholder_hash_value_for_tests");
        u.setEmailVerified(true);
        return u;
    }
}
