package StudySyncer;

import StudySyncer.entity.User;
import StudySyncer.repository.ExamRepository;
import StudySyncer.repository.TaskRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class BulkImportControllerIT {

    @Autowired private MockMvc        mockMvc;
    @Autowired private UserRepository userRepo;
    @Autowired private TaskRepository taskRepo;
    @Autowired private ExamRepository examRepo;

    private User            user;
    private MockHttpSession session;

    @BeforeEach
    void setUp() {
        examRepo.deleteAll();
        taskRepo.deleteAll();
        userRepo.deleteAll();
        user = userRepo.save(buildUser("bulk_user"));
        session = new MockHttpSession();
        session.setAttribute("userId", user.getId());
    }

    @AfterEach
    void tearDown() {
        examRepo.deleteAll();
        taskRepo.deleteAll();
        userRepo.deleteAll();
    }

    // ── 401 when not authenticated ─────────────────────────────

    @Test
    void bulkImport_noSession_returns401() throws Exception {
        mockMvc.perform(post("/api/bulk/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[]}"))
                .andExpect(status().isUnauthorized());
    }

    // ── Empty items array → 400 ────────────────────────────────

    @Test
    void bulkImport_emptyItems_returns400() throws Exception {
        mockMvc.perform(post("/api/bulk/import")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    // ── Happy path: mixed tasks + exams all saved ─────────────

    @Test
    void bulkImport_mixedBatch_allSaved() throws Exception {
        String body = """
                {
                  "items": [
                    {"kind":"task","title":"HW 1","dueDate":"2026-05-12",
                     "taskType":"HOMEWORK","course":"EECS 281"},
                    {"kind":"task","title":"Reading 3","dueDate":"2026-05-20",
                     "taskType":"READING","course":"EECS 281"},
                    {"kind":"exam","title":"Midterm 1",
                     "dateTime":"2026-06-15T09:00:00-04:00",
                     "course":"EECS 281","material":"Chapters 1-5"}
                  ]
                }
                """;

        mockMvc.perform(post("/api/bulk/import")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasksCreated").value(2))
                .andExpect(jsonPath("$.examsCreated").value(1))
                .andExpect(jsonPath("$.errors.length()").value(0));

        assertThat(taskRepo.findAll()).hasSize(2);
        assertThat(examRepo.findAll()).hasSize(1);
    }

    // ── One invalid item aborts the whole batch ───────────────

    @Test
    void bulkImport_oneInvalidItem_rollsBackWholeBatch() throws Exception {
        String body = """
                {
                  "items": [
                    {"kind":"task","title":"Valid task","dueDate":"2026-05-12",
                     "taskType":"HOMEWORK"},
                    {"kind":"task","title":"Missing due date"},
                    {"kind":"exam","title":"Valid exam",
                     "dateTime":"2026-06-15T09:00:00-04:00"}
                  ]
                }
                """;

        mockMvc.perform(post("/api/bulk/import")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.tasksCreated").value(0))
                .andExpect(jsonPath("$.examsCreated").value(0))
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].index").value(1))
                .andExpect(jsonPath("$.errors[0].error").exists());

        // Nothing should have been saved.
        assertThat(taskRepo.findAll()).isEmpty();
        assertThat(examRepo.findAll()).isEmpty();
    }

    // ── Unknown `kind` surfaces as a clean validation error ──

    @Test
    void bulkImport_unknownKind_returns400WithError() throws Exception {
        String body = """
                {
                  "items": [
                    {"kind":"mystery","title":"What am I"}
                  ]
                }
                """;

        mockMvc.perform(post("/api/bulk/import")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].error")
                        .value(org.hamcrest.Matchers.containsString("Unknown kind")));
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
