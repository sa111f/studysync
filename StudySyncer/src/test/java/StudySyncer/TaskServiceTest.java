package StudySyncer;

import StudySyncer.dto.TaskRequest;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Service-level tests that exercise the ownership check, default-application
 * on create, completedAt transitions on update/patch, and the overdue filter.
 *
 * These cover the pieces that don't need MVC wiring (isolation reduces test
 * surface area vs. always going through the controller).
 */
@SpringBootTest
@ActiveProfiles("test")
class TaskServiceTest {

    @Autowired private TaskService    taskService;
    @Autowired private TaskRepository taskRepo;
    @Autowired private UserRepository userRepo;

    private User owner;
    private User stranger;

    @BeforeEach
    void setUp() {
        taskRepo.deleteAll();
        userRepo.deleteAll();

        owner    = userRepo.save(testUser("task_svc_owner"));
        stranger = userRepo.save(testUser("task_svc_stranger"));
    }

    @AfterEach
    void tearDown() {
        taskRepo.deleteAll();
        userRepo.deleteAll();
    }

    // ── Create applies defaults + persists ─────────────────────────────

    @Test
    void create_appliesDefaultsWhenEnumFieldsOmitted() {
        TaskRequest req = new TaskRequest();
        req.setTitle("Read chapter 4");
        req.setDueDate(LocalDate.of(2026, 5, 1));
        // type / priority / status intentionally null

        Task saved = taskService.create(owner, req);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getType())     .isEqualTo(TaskType.OTHER);
        assertThat(saved.getPriority()) .isEqualTo(Priority.MEDIUM);
        assertThat(saved.getStatus())   .isEqualTo(TaskStatus.NOT_STARTED);
        assertThat(saved.getCompletedAt()).isNull();
    }

    // ── Ownership: get another user's task throws NotFound ─────────────

    @Test
    void get_throwsResourceNotFoundWhenTaskBelongsToAnotherUser() {
        Task ownersTask = makeTask(owner, "Mine", LocalDate.of(2026, 5, 1), TaskStatus.NOT_STARTED);
        taskRepo.save(ownersTask);

        assertThatThrownBy(() -> taskService.get(stranger, ownersTask.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void get_throwsResourceNotFoundWhenTaskDoesNotExist() {
        assertThatThrownBy(() -> taskService.get(owner, 99999L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── completedAt transitions ────────────────────────────────────────

    @Test
    void updateStatus_setsCompletedAtWhenBecomingCompleted() {
        Task t = taskRepo.save(makeTask(owner, "Flips",
                LocalDate.of(2026, 5, 1), TaskStatus.IN_PROGRESS));
        assertThat(t.getCompletedAt()).isNull();

        Task updated = taskService.updateStatus(owner, t.getId(), TaskStatus.COMPLETED);

        assertThat(updated.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(updated.getCompletedAt()).isNotNull();
    }

    @Test
    void updateStatus_clearsCompletedAtWhenLeavingCompleted() {
        Task t = makeTask(owner, "Undo", LocalDate.of(2026, 5, 1), TaskStatus.IN_PROGRESS);
        taskRepo.save(t);
        // First transition → COMPLETED (stamps completedAt)
        taskService.updateStatus(owner, t.getId(), TaskStatus.COMPLETED);
        // Then revert
        Task reverted = taskService.updateStatus(owner, t.getId(), TaskStatus.NOT_STARTED);

        assertThat(reverted.getStatus()).isEqualTo(TaskStatus.NOT_STARTED);
        assertThat(reverted.getCompletedAt()).isNull();
    }

    // ── Full update: required enums + ownership ─────────────────────────

    @Test
    void update_throwsIllegalArgWhenStatusMissing() {
        Task t = taskRepo.save(makeTask(owner, "Missing enum",
                LocalDate.of(2026, 5, 1), TaskStatus.NOT_STARTED));

        TaskRequest req = new TaskRequest();
        req.setTitle("Missing enum");
        req.setDueDate(LocalDate.of(2026, 5, 1));
        req.setType(TaskType.ASSIGNMENT);
        req.setPriority(Priority.LOW);
        // status intentionally null — update requires it

        assertThatThrownBy(() -> taskService.update(owner, t.getId(), req))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void update_throwsResourceNotFoundWhenTaskBelongsToAnotherUser() {
        Task ownersTask = taskRepo.save(makeTask(owner, "Not yours",
                LocalDate.of(2026, 5, 1), TaskStatus.NOT_STARTED));

        TaskRequest req = fullReq("Not yours", LocalDate.of(2026, 5, 2), TaskStatus.COMPLETED);

        assertThatThrownBy(() -> taskService.update(stranger, ownersTask.getId(), req))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── Delete: ownership ──────────────────────────────────────────────

    @Test
    void delete_throwsResourceNotFoundWhenTaskBelongsToAnotherUser() {
        Task ownersTask = taskRepo.save(makeTask(owner, "Not yours",
                LocalDate.of(2026, 5, 1), TaskStatus.NOT_STARTED));

        assertThatThrownBy(() -> taskService.delete(stranger, ownersTask.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(taskRepo.findById(ownersTask.getId())).isPresent();
    }

    // ── Overdue filter ─────────────────────────────────────────────────

    @Test
    void listForUser_overdue_returnsOnlyPastDueActiveTasks() {
        LocalDate today = LocalDate.of(2026, 5, 15);

        Task overdueActive = taskRepo.save(makeTask(owner,
                "Overdue active", today.minusDays(3), TaskStatus.NOT_STARTED));
        taskRepo.save(makeTask(owner,
                "Overdue but completed", today.minusDays(1), TaskStatus.COMPLETED));
        taskRepo.save(makeTask(owner,
                "Future active", today.plusDays(2), TaskStatus.IN_PROGRESS));

        var results = taskService.listForUser(owner, "overdue", today);

        assertThat(results)
                .hasSize(1)
                .extracting(Task::getId)
                .containsExactly(overdueActive.getId());
    }

    // ── Active filter sorts by dueDate then createdAt ──────────────────

    @Test
    void listForUser_active_mergesNotStartedAndInProgressSortedByDueDate() {
        taskRepo.save(makeTask(owner, "C later",  LocalDate.of(2026, 5, 10), TaskStatus.NOT_STARTED));
        taskRepo.save(makeTask(owner, "A first",  LocalDate.of(2026, 5,  1), TaskStatus.IN_PROGRESS));
        taskRepo.save(makeTask(owner, "B middle", LocalDate.of(2026, 5,  5), TaskStatus.NOT_STARTED));
        taskRepo.save(makeTask(owner, "D done",   LocalDate.of(2026, 5,  1), TaskStatus.COMPLETED));

        var results = taskService.listForUser(owner, "active", LocalDate.of(2026, 5, 15));

        assertThat(results)
                .extracting(Task::getTitle)
                .containsExactly("A first", "B middle", "C later");
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private User testUser(String username) {
        User u = new User();
        u.setUsername(username);
        u.setEmail(username + "@test.example");
        u.setPasswordHash("$2a$10$placeholder_hash_value_for_tests");
        u.setEmailVerified(true);
        return u;
    }

    private Task makeTask(User user, String title, LocalDate due, TaskStatus status) {
        Task t = new Task();
        t.setUser(user);
        t.setTitle(title);
        t.setDueDate(due);
        t.setStatus(status);
        return t;
    }

    private TaskRequest fullReq(String title, LocalDate due, TaskStatus status) {
        TaskRequest req = new TaskRequest();
        req.setTitle(title);
        req.setDueDate(due);
        req.setType(TaskType.OTHER);
        req.setPriority(Priority.MEDIUM);
        req.setStatus(status);
        return req;
    }
}
