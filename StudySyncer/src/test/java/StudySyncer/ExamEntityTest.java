package StudySyncer;

import StudySyncer.entity.Exam;
import StudySyncer.entity.User;
import StudySyncer.repository.ExamRepository;
import StudySyncer.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for the Exam entity and ExamRepository.
 */
@SpringBootTest
@ActiveProfiles("test")
class ExamEntityTest {

    @Autowired private ExamRepository examRepo;
    @Autowired private UserRepository userRepo;

    @AfterEach
    void cleanup() {
        examRepo.deleteAll();
        userRepo.deleteAll();
    }

    @Test
    void save_stampsCreatedAtViaPrePersist() {
        User user = userRepo.save(testUser("exam_persist_user"));

        Exam e = new Exam();
        e.setUser(user);
        e.setTitle("Calculus midterm");
        e.setDateTime(LocalDateTime.of(2026, 5, 10, 14, 0));

        Exam saved = examRepo.saveAndFlush(e);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void save_throwsWhenTitleIsNull() {
        User user = userRepo.save(testUser("exam_null_title"));

        Exam e = new Exam();
        e.setUser(user);
        e.setDateTime(LocalDateTime.of(2026, 5, 10, 14, 0));

        assertThatThrownBy(() -> examRepo.saveAndFlush(e))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void save_throwsWhenDateTimeIsNull() {
        User user = userRepo.save(testUser("exam_null_dt"));

        Exam e = new Exam();
        e.setUser(user);
        e.setTitle("No time");

        assertThatThrownBy(() -> examRepo.saveAndFlush(e))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void findByUserOrderByDateTimeAsc_ordersChronologically() {
        User user = userRepo.save(testUser("exam_order_user"));

        examRepo.save(newExam(user, "Later",   LocalDateTime.of(2026, 6, 1, 10, 0)));
        examRepo.save(newExam(user, "Sooner",  LocalDateTime.of(2026, 5, 1, 10, 0)));
        examRepo.save(newExam(user, "Middle",  LocalDateTime.of(2026, 5, 15, 10, 0)));

        assertThat(examRepo.findByUserOrderByDateTimeAsc(user))
                .extracting(Exam::getTitle)
                .containsExactly("Sooner", "Middle", "Later");
    }

    @Test
    void findByUserAndDateTimeAfterOrderByDateTimeAsc_excludesPastExams() {
        User user = userRepo.save(testUser("exam_future_user"));
        LocalDateTime pivot = LocalDateTime.of(2026, 5, 15, 12, 0);

        examRepo.save(newExam(user, "Past",   pivot.minusDays(1)));
        Exam upcoming = examRepo.save(newExam(user, "Upcoming", pivot.plusDays(2)));

        var results = examRepo.findByUserAndDateTimeAfterOrderByDateTimeAsc(user, pivot);

        assertThat(results)
                .hasSize(1)
                .extracting(Exam::getId)
                .containsExactly(upcoming.getId());
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

    private Exam newExam(User user, String title, LocalDateTime when) {
        Exam e = new Exam();
        e.setUser(user);
        e.setTitle(title);
        e.setDateTime(when);
        return e;
    }
}
