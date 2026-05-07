package StudySyncer;

import StudySyncer.dto.DigestContent;
import StudySyncer.entity.Exam;
import StudySyncer.entity.Task;
import StudySyncer.entity.TaskStatus;
import StudySyncer.entity.User;
import StudySyncer.repository.ExamRepository;
import StudySyncer.repository.TaskRepository;
import StudySyncer.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Builder tests for the digest shouldSend gate (spec 8.3).
 * Uses real JPA repositories — write + read matches what the scheduler does.
 */
@SpringBootTest
@ActiveProfiles("test")
class DigestServiceTest {

    @Autowired private DigestService   digestService;
    @Autowired private UserRepository  userRepo;
    @Autowired private TaskRepository  taskRepo;
    @Autowired private ExamRepository  examRepo;

    private User      user;
    private LocalDate today = LocalDate.of(2026, 5, 15);   // Friday

    @BeforeEach
    void setUp() {
        examRepo.deleteAll();
        taskRepo.deleteAll();
        userRepo.deleteAll();
        user = userRepo.save(buildUser("digest_user"));
    }

    @AfterEach
    void tearDown() {
        examRepo.deleteAll();
        taskRepo.deleteAll();
        userRepo.deleteAll();
    }

    // ── Empty state → shouldSend false ─────────────────────────

    @Test
    void buildDigest_nothingToShow_shouldSendFalse() {
        DigestContent c = digestService.buildDigest(user, today);
        assertThat(c.isShouldSend()).isFalse();
        assertThat(c.getTasksDueToday()).isEmpty();
        assertThat(c.getTasksOverdue()).isEmpty();
        assertThat(c.getExamsThisWeek()).isEmpty();
    }

    // ── Task due today → true ──────────────────────────────────

    @Test
    void buildDigest_oneTaskDueToday_shouldSendTrue() {
        taskRepo.save(newTask("Read ch 3", today, TaskStatus.NOT_STARTED));
        DigestContent c = digestService.buildDigest(user, today);
        assertThat(c.isShouldSend()).isTrue();
        assertThat(c.getTasksDueToday()).hasSize(1);
    }

    // ── Only overdue → true ────────────────────────────────────

    @Test
    void buildDigest_onlyOverdueActive_shouldSendTrue() {
        taskRepo.save(newTask("Late lab",    today.minusDays(3), TaskStatus.IN_PROGRESS));
        taskRepo.save(newTask("Done chore",  today.minusDays(5), TaskStatus.COMPLETED));
        DigestContent c = digestService.buildDigest(user, today);
        assertThat(c.isShouldSend()).isTrue();
        assertThat(c.getTasksOverdue()).extracting(Task::getTitle).containsExactly("Late lab");
        assertThat(c.getTasksDueToday()).isEmpty();
    }

    // ── Only exam this week → true ─────────────────────────────

    @Test
    void buildDigest_onlyExamThisWeek_shouldSendTrue() {
        // today is Friday 2026-05-15 → thisSunday = 2026-05-17.
        examRepo.save(newExam("Midterm", today.plusDays(1).atTime(10, 0))); // Saturday
        DigestContent c = digestService.buildDigest(user, today);
        assertThat(c.isShouldSend()).isTrue();
        assertThat(c.getExamsThisWeek()).hasSize(1);
    }

    @Test
    void buildDigest_examNextWeek_doesNotTriggerDigest() {
        examRepo.save(newExam("Future midterm", today.plusDays(10).atTime(10, 0)));
        DigestContent c = digestService.buildDigest(user, today);
        assertThat(c.getExamsThisWeek()).isEmpty();
        assertThat(c.isShouldSend()).isFalse();
    }

    // ── Completed tasks due today are NOT listed ─────────────

    @Test
    void buildDigest_completedTaskDueToday_notIncluded() {
        taskRepo.save(newTask("Already done", today, TaskStatus.COMPLETED));
        DigestContent c = digestService.buildDigest(user, today);
        assertThat(c.getTasksDueToday()).isEmpty();
        assertThat(c.isShouldSend()).isFalse();
    }

    // ── Helpers ────────────────────────────────────────────────

    private Task newTask(String title, LocalDate due, TaskStatus status) {
        Task t = new Task();
        t.setUser(user);
        t.setTitle(title);
        t.setDueDate(due);
        t.setStatus(status);
        return t;
    }

    private Exam newExam(String title, LocalDateTime when) {
        Exam e = new Exam();
        e.setUser(user);
        e.setTitle(title);
        e.setDateTime(when);
        return e;
    }

    private User buildUser(String username) {
        User u = new User();
        u.setUsername(username);
        u.setEmail(username + "@test.example");
        u.setPasswordHash("$2a$10$placeholder_hash_value_for_tests");
        u.setEmailVerified(true);
        u.setAccountabilityEmail(username + "-acct@test.example");
        return u;
    }
}
