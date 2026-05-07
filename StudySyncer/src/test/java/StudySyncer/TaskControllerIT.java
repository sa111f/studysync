package StudySyncer;

import StudySyncer.entity.Priority;
import StudySyncer.entity.Task;
import StudySyncer.entity.TaskStatus;
import StudySyncer.entity.TaskType;
import StudySyncer.entity.User;
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

import java.time.LocalDate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for TaskController, matching the style of
 * DailyGoalControllerIT: MockMvc with filters disabled, session userId
 * set manually to simulate a logged-in user.
 *
 * Coverage (from spec 2.5):
 *   - Create task → 201 + persisted
 *   - Create with missing title → 400
 *   - Get my task → 200
 *   - Get another user's task → 404
 *   - Update task status → completedAt set / cleared correctly
 *   - Delete another user's task → 404
 *   - Overdue filter returns only overdue non-completed tasks
 *   - Unauthenticated → 401
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class TaskControllerIT {

    @Autowired private MockMvc        mockMvc;
    @Autowired private UserRepository userRepo;
    @Autowired private TaskRepository taskRepo;

    private User             owner;
    private User             stranger;
    private MockHttpSession  ownerSession;
    private MockHttpSession  strangerSession;

    @BeforeEach
    void setUp() {
        taskRepo.deleteAll();
        userRepo.deleteAll();

        owner    = userRepo.save(buildUser("task_ctrl_owner"));
        stranger = userRepo.save(buildUser("task_ctrl_stranger"));

        ownerSession    = new MockHttpSession();
        ownerSession.setAttribute("userId", owner.getId());
        strangerSession = new MockHttpSession();
        strangerSession.setAttribute("userId", stranger.getId());
    }

    @AfterEach
    void tearDown() {
        taskRepo.deleteAll();
        userRepo.deleteAll();
    }

    // ── Create → 201 + persisted ────────────────────────────────────────

    @Test
    void create_withValidBody_returns201AndPersists() throws Exception {
        String body = """
                {
                  "title": "Finish lab report",
                  "dueDate": "2026-05-10",
                  "course": "PHYS 201",
                  "type": "LAB",
                  "priority": "HIGH"
                }
                """;

        mockMvc.perform(post("/api/tasks")
                        .session(ownerSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.title").value("Finish lab report"))
                .andExpect(jsonPath("$.type").value("LAB"))
                .andExpect(jsonPath("$.priority").value("HIGH"))
                .andExpect(jsonPath("$.status").value("NOT_STARTED"))   // default
                .andExpect(jsonPath("$.overdue").value(false));

        // Persistence sanity
        var saved = taskRepo.findAll();
        org.assertj.core.api.Assertions.assertThat(saved).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(saved.get(0).getUser().getId()).isEqualTo(owner.getId());
    }

    // ── Missing title → 400 ─────────────────────────────────────────────

    @Test
    void create_withMissingTitle_returns400() throws Exception {
        String body = """
                { "dueDate": "2026-05-10" }
                """;

        mockMvc.perform(post("/api/tasks")
                        .session(ownerSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    // ── Get my task → 200 ───────────────────────────────────────────────

    @Test
    void get_myTask_returns200() throws Exception {
        Task mine = taskRepo.save(buildTask(owner, "Mine",
                LocalDate.of(2026, 5, 10), TaskStatus.NOT_STARTED));

        mockMvc.perform(get("/api/tasks/" + mine.getId()).session(ownerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(mine.getId()))
                .andExpect(jsonPath("$.title").value("Mine"));
    }

    // ── Get another user's task → 404 ───────────────────────────────────

    @Test
    void get_anotherUsersTask_returns404() throws Exception {
        Task owners = taskRepo.save(buildTask(owner, "Private",
                LocalDate.of(2026, 5, 10), TaskStatus.NOT_STARTED));

        mockMvc.perform(get("/api/tasks/" + owners.getId()).session(strangerSession))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    // ── Status PATCH: completedAt transitions ───────────────────────────

    @Test
    void patchStatus_toCompletedStampsAndRevertClears() throws Exception {
        Task t = taskRepo.save(buildTask(owner, "Toggles",
                LocalDate.of(2026, 5, 10), TaskStatus.IN_PROGRESS));

        // IN_PROGRESS → COMPLETED (stamps)
        mockMvc.perform(patch("/api/tasks/" + t.getId() + "/status")
                        .session(ownerSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"COMPLETED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.completedAt").exists());

        // COMPLETED → NOT_STARTED (clears)
        mockMvc.perform(patch("/api/tasks/" + t.getId() + "/status")
                        .session(ownerSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"NOT_STARTED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOT_STARTED"))
                .andExpect(jsonPath("$.completedAt").doesNotExist());
    }

    // ── Delete another user's task → 404 ────────────────────────────────

    @Test
    void delete_anotherUsersTask_returns404AndDoesNotDelete() throws Exception {
        Task owners = taskRepo.save(buildTask(owner, "Guarded",
                LocalDate.of(2026, 5, 10), TaskStatus.NOT_STARTED));

        mockMvc.perform(delete("/api/tasks/" + owners.getId()).session(strangerSession))
                .andExpect(status().isNotFound());

        org.assertj.core.api.Assertions.assertThat(taskRepo.findById(owners.getId())).isPresent();
    }

    // ── Overdue filter → only overdue non-completed ─────────────────────

    @Test
    void list_overdueFilter_returnsOnlyOverdueActiveTasks() throws Exception {
        LocalDate today = LocalDate.now();

        Task overdueActive = taskRepo.save(buildTask(owner,
                "Overdue active", today.minusDays(3), TaskStatus.NOT_STARTED));
        taskRepo.save(buildTask(owner,
                "Overdue done", today.minusDays(1), TaskStatus.COMPLETED));
        taskRepo.save(buildTask(owner,
                "Future active", today.plusDays(2), TaskStatus.IN_PROGRESS));

        mockMvc.perform(get("/api/tasks?status=overdue").session(ownerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(overdueActive.getId()))
                .andExpect(jsonPath("$[0].overdue").value(true));
    }

    // ── Unauthenticated → 401 ───────────────────────────────────────────

    @Test
    void list_withoutSession_returns401() throws Exception {
        // No .session(...) — no userId attribute
        mockMvc.perform(get("/api/tasks"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").exists());
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private User buildUser(String username) {
        User u = new User();
        u.setUsername(username);
        u.setEmail(username + "@test.example");
        u.setPasswordHash("$2a$10$placeholder_hash_value_for_tests");
        u.setEmailVerified(true);
        return u;
    }

    private Task buildTask(User user, String title, LocalDate due, TaskStatus status) {
        Task t = new Task();
        t.setUser(user);
        t.setTitle(title);
        t.setDueDate(due);
        t.setStatus(status);
        t.setType(TaskType.OTHER);
        t.setPriority(Priority.MEDIUM);
        return t;
    }
}
