package StudySyncer;

import StudySyncer.entity.Priority;
import StudySyncer.entity.Task;
import StudySyncer.entity.TaskStatus;
import StudySyncer.entity.TaskType;
import StudySyncer.entity.User;
import StudySyncer.repository.TaskRepository;
import StudySyncer.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for the Task entity and TaskRepository.
 *
 * Coverage:
 *   - Defaults (type/priority/status) are applied on persist when not set
 *   - @PrePersist stamps createdAt
 *   - Required columns (title, dueDate) raise on null
 *   - Custom repository finders return the expected rows
 */
@SpringBootTest
@ActiveProfiles("test")
class TaskEntityTest {

    @Autowired private TaskRepository taskRepo;
    @Autowired private UserRepository userRepo;

    @AfterEach
    void cleanup() {
        taskRepo.deleteAll();
        userRepo.deleteAll();
    }

    // ── Defaults + @PrePersist ─────────────────────────────────────────────

    @Test
    void save_appliesEnumDefaultsAndStampsCreatedAt() {
        User user = userRepo.save(testUser("task_defaults_user"));

        Task t = new Task();
        t.setUser(user);
        t.setTitle("Read chapter 4");
        t.setDueDate(LocalDate.of(2026, 5, 1));
        // type / priority / status intentionally not set

        Task saved = taskRepo.saveAndFlush(t);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getType())     .isEqualTo(TaskType.OTHER);
        assertThat(saved.getPriority()) .isEqualTo(Priority.MEDIUM);
        assertThat(saved.getStatus())   .isEqualTo(TaskStatus.NOT_STARTED);
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getCompletedAt()).isNull();
    }

    // ── Required columns ────────────────────────────────────────────────────

    @Test
    void save_throwsWhenTitleIsNull() {
        User user = userRepo.save(testUser("task_null_title_user"));

        Task t = new Task();
        t.setUser(user);
        t.setDueDate(LocalDate.of(2026, 5, 1));
        // title omitted

        assertThatThrownBy(() -> taskRepo.saveAndFlush(t))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void save_throwsWhenDueDateIsNull() {
        User user = userRepo.save(testUser("task_null_due_user"));

        Task t = new Task();
        t.setUser(user);
        t.setTitle("Missing due date");

        assertThatThrownBy(() -> taskRepo.saveAndFlush(t))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ── Repository finders ──────────────────────────────────────────────────

    @Test
    void findByUserAndStatus_returnsOnlyMatchingStatus() {
        User user = userRepo.save(testUser("task_filter_user"));

        taskRepo.save(newTask(user, "In progress", LocalDate.of(2026, 5, 1),
                TaskStatus.IN_PROGRESS));
        taskRepo.save(newTask(user, "Done",        LocalDate.of(2026, 5, 2),
                TaskStatus.COMPLETED));
        taskRepo.save(newTask(user, "Not started", LocalDate.of(2026, 5, 3),
                TaskStatus.NOT_STARTED));

        assertThat(taskRepo.findByUserAndStatus(user, TaskStatus.COMPLETED))
                .hasSize(1).extracting(Task::getTitle).containsExactly("Done");

        assertThat(taskRepo.findByUserAndStatus(user, TaskStatus.NOT_STARTED))
                .hasSize(1).extracting(Task::getTitle).containsExactly("Not started");
    }

    @Test
    void findByUserOrderByDueDateAsc_ordersBySoonestDueFirst() {
        User user = userRepo.save(testUser("task_order_user"));

        taskRepo.save(newTask(user, "C", LocalDate.of(2026, 5, 10), TaskStatus.NOT_STARTED));
        taskRepo.save(newTask(user, "A", LocalDate.of(2026, 5,  1), TaskStatus.NOT_STARTED));
        taskRepo.save(newTask(user, "B", LocalDate.of(2026, 5,  5), TaskStatus.NOT_STARTED));

        assertThat(taskRepo.findByUserOrderByDueDateAsc(user))
                .extracting(Task::getTitle)
                .containsExactly("A", "B", "C");
    }

    @Test
    void findByUserAndDueDateBeforeAndStatusNot_returnsOverdueActiveTasks() {
        User user = userRepo.save(testUser("task_overdue_user"));
        LocalDate today = LocalDate.of(2026, 5, 15);

        // Overdue + active — should be in the result
        Task overdueActive = taskRepo.save(newTask(user,
                "Overdue active", today.minusDays(3), TaskStatus.NOT_STARTED));
        // Overdue but completed — excluded by the statusNot filter
        taskRepo.save(newTask(user,
                "Overdue done", today.minusDays(1), TaskStatus.COMPLETED));
        // Future + active — excluded by the dueDateBefore filter
        taskRepo.save(newTask(user,
                "Future active", today.plusDays(2), TaskStatus.NOT_STARTED));

        var results = taskRepo.findByUserAndDueDateBeforeAndStatusNot(
                user, today, TaskStatus.COMPLETED);

        assertThat(results)
                .hasSize(1)
                .extracting(Task::getId)
                .containsExactly(overdueActive.getId());
    }

    @Test
    void countByUserAndStatus_returnsPerStatusCount() {
        User user = userRepo.save(testUser("task_count_user"));

        taskRepo.save(newTask(user, "a", LocalDate.of(2026, 5, 1), TaskStatus.COMPLETED));
        taskRepo.save(newTask(user, "b", LocalDate.of(2026, 5, 2), TaskStatus.COMPLETED));
        taskRepo.save(newTask(user, "c", LocalDate.of(2026, 5, 3), TaskStatus.NOT_STARTED));

        assertThat(taskRepo.countByUserAndStatus(user, TaskStatus.COMPLETED)).isEqualTo(2L);
        assertThat(taskRepo.countByUserAndStatus(user, TaskStatus.NOT_STARTED)).isEqualTo(1L);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private User testUser(String username) {
        User u = new User();
        u.setUsername(username);
        u.setEmail(username + "@test.example");
        u.setPasswordHash("$2a$10$placeholder_hash_value_for_tests");
        u.setEmailVerified(true);
        return u;
    }

    private Task newTask(User user, String title, LocalDate due, TaskStatus status) {
        Task t = new Task();
        t.setUser(user);
        t.setTitle(title);
        t.setDueDate(due);
        t.setStatus(status);
        return t;
    }
}
