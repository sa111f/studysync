package StudySyncer;

import StudySyncer.dto.ParsedSyllabusResult;
import StudySyncer.dto.SyllabusItem;
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
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for SyllabusController. The PDF extraction + AI
 * services are mocked so tests are fast and deterministic.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class SyllabusControllerIT {

    @Autowired private MockMvc              mockMvc;
    @Autowired private UserRepository       userRepo;
    @Autowired private SyllabusRateLimiter  rateLimiter;

    @MockBean private PdfExtractionService     pdfService;
    @MockBean private AiSyllabusParserService  aiService;

    private User             user;
    private MockHttpSession  session;

    @BeforeEach
    void setUp() {
        rateLimiter.reset();
        userRepo.deleteAll();

        user = userRepo.save(buildUser("syll_it_user"));
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
    void upload_noSession_returns401() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "syllabus.pdf", "application/pdf", "%PDF-1.4\n".getBytes());
        mockMvc.perform(multipart("/api/syllabus/upload").file(file))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").exists());
    }

    // ── 400 when file is not a PDF ─────────────────────────────

    @Test
    void upload_nonPdf_returns400WithoutBurningRateLimit() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "notes.txt", "text/plain", "hello".getBytes());
        mockMvc.perform(multipart("/api/syllabus/upload")
                        .file(file).session(session))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Only PDF files are accepted."));
    }

    // ── 400 when PdfExtractionService throws (e.g. encrypted) ─

    @Test
    void upload_encryptedPdf_returns400WithClearMessage() throws Exception {
        when(pdfService.extractText(any(byte[].class)))
                .thenThrow(new PdfExtractionException(
                        "This PDF is password-protected. Remove the password and try again."));

        MockMultipartFile file = new MockMultipartFile(
                "file", "locked.pdf", "application/pdf", "%PDF-1.4\n".getBytes());
        mockMvc.perform(multipart("/api/syllabus/upload")
                        .file(file).session(session))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("password-protected")));
    }

    // ── 200 happy path ─────────────────────────────────────────

    @Test
    void upload_happyPath_returns200WithItemsAndCourseCode() throws Exception {
        when(pdfService.extractText(any(byte[].class)))
                .thenReturn("EECS 281 syllabus text…");

        List<SyllabusItem> items = List.of(
                SyllabusItem.task("HW 1", LocalDate.of(2026, 5, 12),
                        TaskType.HOMEWORK, "EECS 281", null),
                SyllabusItem.exam("Midterm 1",
                        Instant.parse("2026-06-15T13:00:00Z"),
                        "EECS 281", "Chapters 1-5"));

        when(aiService.parseSyllabus(anyString(), any()))
                .thenReturn(ParsedSyllabusResult.success("EECS 281", items, false));

        MockMultipartFile file = new MockMultipartFile(
                "file", "syllabus.pdf", "application/pdf", "%PDF-1.4\n".getBytes());

        mockMvc.perform(multipart("/api/syllabus/upload")
                        .file(file)
                        .param("timezone", "America/Toronto")
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.courseCode").value("EECS 281"))
                .andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].kind").value("task"))
                .andExpect(jsonPath("$.items[0].title").value("HW 1"))
                .andExpect(jsonPath("$.items[1].kind").value("exam"))
                .andExpect(jsonPath("$.items[1].title").value("Midterm 1"));
    }

    // ── 503 when AI is unavailable ─────────────────────────────

    @Test
    void upload_serviceReportsAiUnavailable_returns503() throws Exception {
        when(pdfService.extractText(any(byte[].class))).thenReturn("text");
        when(aiService.parseSyllabus(anyString(), any()))
                .thenReturn(ParsedSyllabusResult.failure(
                        ParsedSyllabusResult.FailureReason.AI_UNAVAILABLE,
                        "Not configured"));

        MockMultipartFile file = new MockMultipartFile(
                "file", "x.pdf", "application/pdf", "%PDF-1.4\n".getBytes());

        mockMvc.perform(multipart("/api/syllabus/upload")
                        .file(file).session(session))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").exists());
    }

    // ── 429 after hitting the 5/hr rate limit ──────────────────

    @Test
    void upload_rateLimitExceeded_returns429WithRetryAfter() throws Exception {
        when(pdfService.extractText(any(byte[].class))).thenReturn("text");
        when(aiService.parseSyllabus(anyString(), any()))
                .thenReturn(ParsedSyllabusResult.success(null, List.of(), false));

        for (int i = 0; i < SyllabusRateLimiter.MAX_CALLS; i++) {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "s" + i + ".pdf", "application/pdf", "%PDF-1.4\n".getBytes());
            mockMvc.perform(multipart("/api/syllabus/upload")
                            .file(file).session(session))
                    .andExpect(status().isOk());
        }

        MockMultipartFile overflow = new MockMultipartFile(
                "file", "over.pdf", "application/pdf", "%PDF-1.4\n".getBytes());
        mockMvc.perform(multipart("/api/syllabus/upload")
                        .file(overflow).session(session))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.retryAfterSeconds").isNumber());
    }

    // ── Helpers ────────────────────────────────────────────────

    private User buildUser(String username) {
        User u = new User();
        u.setUsername(username);
        u.setEmail(username + "@test.example");
        u.setPasswordHash("$2a$10$placeholder_hash_value_for_tests");
        u.setEmailVerified(true);
        return u;
    }
}
