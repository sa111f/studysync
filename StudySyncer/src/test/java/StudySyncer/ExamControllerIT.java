package StudySyncer;

import StudySyncer.entity.Exam;
import StudySyncer.entity.User;
import StudySyncer.repository.ExamRepository;
import StudySyncer.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class ExamControllerIT {

    @Autowired private MockMvc        mockMvc;
    @Autowired private UserRepository userRepo;
    @Autowired private ExamRepository examRepo;

    private User            owner;
    private User            stranger;
    private MockHttpSession ownerSession;
    private MockHttpSession strangerSession;

    @BeforeEach
    void setUp() {
        examRepo.deleteAll();
        userRepo.deleteAll();
        owner    = userRepo.save(buildUser("exam_ctrl_owner"));
        stranger = userRepo.save(buildUser("exam_ctrl_stranger"));

        ownerSession    = new MockHttpSession();
        ownerSession.setAttribute("userId", owner.getId());
        strangerSession = new MockHttpSession();
        strangerSession.setAttribute("userId", stranger.getId());
    }

    @AfterEach
    void tearDown() {
        examRepo.deleteAll();
        userRepo.deleteAll();
    }

    // ── 401 when not authenticated ─────────────────────────────────

    @Test
    void list_withoutSession_returns401() throws Exception {
        mockMvc.perform(get("/api/exams"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").exists());
    }

    // ── Create → 201 + persisted (shape B) ─────────────────────────

    @Test
    void create_withShapeBBody_returns201AndPersists() throws Exception {
        String body = """
                {
                  "title": "Midterm 1",
                  "course": "EECS 281",
                  "date": "2026-06-15",
                  "time": "14:00",
                  "timezone": "America/Toronto",
                  "location": "Dennis 109"
                }
                """;

        mockMvc.perform(post("/api/exams")
                        .session(ownerSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.title").value("Midterm 1"))
                .andExpect(jsonPath("$.course").value("EECS 281"))
                .andExpect(jsonPath("$.location").value("Dennis 109"))
                .andExpect(jsonPath("$.bucket").exists())
                .andExpect(jsonPath("$.daysUntil").exists());

        org.assertj.core.api.Assertions.assertThat(examRepo.findAll()).hasSize(1);
    }

    // ── Create without date/time → 400 ─────────────────────────────

    @Test
    void create_missingDateTime_returns400() throws Exception {
        String body = """
                { "title": "No date" }
                """;
        mockMvc.perform(post("/api/exams")
                        .session(ownerSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void create_missingTitle_returns400() throws Exception {
        String body = """
                { "date": "2026-06-15", "time": "14:00", "timezone": "America/Toronto" }
                """;
        mockMvc.perform(post("/api/exams")
                        .session(ownerSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    // ── Get another user's exam → 404 ──────────────────────────────

    @Test
    void get_anotherUsersExam_returns404() throws Exception {
        Exam e = examRepo.save(buildExam(owner, "Private",
                LocalDateTime.of(2026, 6, 15, 14, 0)));
        mockMvc.perform(get("/api/exams/" + e.getId()).session(strangerSession))
                .andExpect(status().isNotFound());
    }

    // ── /next endpoint respects count + excludes past ──────────────

    @Test
    void nextEndpoint_returnsOnlyFutureAndRespectsCount() throws Exception {
        // Using Toronto clock reference — future exams ordered as spec expects.
        LocalDateTime future = LocalDateTime.now(TrackerService.TORONTO).plusDays(1);
        examRepo.save(buildExam(owner, "Past",
                LocalDateTime.now(TrackerService.TORONTO).minusDays(7)));
        examRepo.save(buildExam(owner, "Soonest", future));
        examRepo.save(buildExam(owner, "Second",  future.plusDays(2)));
        examRepo.save(buildExam(owner, "Third",   future.plusDays(5)));
        examRepo.save(buildExam(owner, "Fourth",  future.plusDays(10)));

        mockMvc.perform(get("/api/exams/next?count=3").session(ownerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].title").value("Soonest"))
                .andExpect(jsonPath("$[1].title").value("Second"))
                .andExpect(jsonPath("$[2].title").value("Third"));
    }

    // ── tz param fallback on invalid value ─────────────────────────

    @Test
    void list_invalidTz_fallsBackToDefaultAndStill200() throws Exception {
        examRepo.save(buildExam(owner, "Upcoming",
                LocalDateTime.now(TrackerService.TORONTO).plusDays(3)));

        mockMvc.perform(get("/api/exams?filter=upcoming&tz=Not/A_Zone!!!").session(ownerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    // ── Delete another user's exam → 404 ───────────────────────────

    @Test
    void delete_anotherUsersExam_returns404AndDoesNotDelete() throws Exception {
        Exam e = examRepo.save(buildExam(owner, "Guarded",
                LocalDateTime.of(2026, 6, 15, 14, 0)));
        mockMvc.perform(delete("/api/exams/" + e.getId()).session(strangerSession))
                .andExpect(status().isNotFound());
        org.assertj.core.api.Assertions.assertThat(examRepo.findById(e.getId())).isPresent();
    }

    // ── Helpers ────────────────────────────────────────────────────

    private User buildUser(String username) {
        User u = new User();
        u.setUsername(username);
        u.setEmail(username + "@test.example");
        u.setPasswordHash("$2a$10$placeholder_hash_value_for_tests");
        u.setEmailVerified(true);
        return u;
    }

    private Exam buildExam(User user, String title, LocalDateTime when) {
        Exam e = new Exam();
        e.setUser(user);
        e.setTitle(title);
        e.setDateTime(when);
        return e;
    }
}
