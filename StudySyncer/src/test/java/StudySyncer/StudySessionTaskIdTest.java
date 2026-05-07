package StudySyncer;

import StudySyncer.entity.StudySession;
import StudySyncer.entity.User;
import StudySyncer.repository.StudySessionRepository;
import StudySyncer.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the new Phase 1 column on study_sessions:
 *   - task_id is a nullable Long column (no FK; soft reference to Task.id)
 *   - existing sessions with null task_id round-trip correctly
 *   - the per-task aggregation query returns 0 for unknown tasks and sums correctly
 */
@SpringBootTest
@ActiveProfiles("test")
class StudySessionTaskIdTest {

    @Autowired private StudySessionRepository sessionRepo;
    @Autowired private UserRepository         userRepo;

    @AfterEach
    void cleanup() {
        sessionRepo.deleteAll();
        userRepo.deleteAll();
    }

    @Test
    void save_acceptsNullTaskId() {
        User user = userRepo.save(testUser("sess_null_task"));

        StudySession s = newSession(user, 25);
        // taskId intentionally left null
        StudySession saved = sessionRepo.saveAndFlush(s);

        assertThat(saved.getTaskId()).isNull();
    }

    @Test
    void save_roundTripsNonNullTaskId() {
        User user = userRepo.save(testUser("sess_with_task"));

        StudySession s = newSession(user, 40);
        s.setTaskId(4242L);  // raw reference — no FK constraint to enforce existence
        StudySession saved = sessionRepo.saveAndFlush(s);

        StudySession reloaded = sessionRepo.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getTaskId()).isEqualTo(4242L);
    }

    @Test
    void sumDurationByUserAndTaskId_returnsZeroWhenNoSessions() {
        User user = userRepo.save(testUser("sess_empty_sum"));

        long total = sessionRepo.sumDurationByUserAndTaskId(user, 999L);
        assertThat(total).isEqualTo(0L);
    }

    @Test
    void sumDurationByUserAndTaskId_sumsMatchingSessions() {
        User user = userRepo.save(testUser("sess_sum_user"));

        StudySession a = newSession(user, 10); a.setTaskId(1L);
        StudySession b = newSession(user, 30); b.setTaskId(1L);
        StudySession c = newSession(user, 25); c.setTaskId(2L);      // different task
        StudySession d = newSession(user, 50); d.setTaskId(null);    // no task

        sessionRepo.save(a);
        sessionRepo.save(b);
        sessionRepo.save(c);
        sessionRepo.save(d);

        assertThat(sessionRepo.sumDurationByUserAndTaskId(user, 1L)).isEqualTo(40L);
        assertThat(sessionRepo.sumDurationByUserAndTaskId(user, 2L)).isEqualTo(25L);
    }

    @Test
    void findByUserAndTaskIdOrderByCompletedAtDesc_returnsOnlyMatchingSessions() {
        User user = userRepo.save(testUser("sess_find_user"));

        StudySession s1 = newSession(user, 10); s1.setTaskId(7L);
        StudySession s2 = newSession(user, 15); s2.setTaskId(7L);
        StudySession other = newSession(user, 20); other.setTaskId(8L);
        sessionRepo.save(s1);
        sessionRepo.save(s2);
        sessionRepo.save(other);

        assertThat(sessionRepo.findByUserAndTaskIdOrderByCompletedAtDesc(user, 7L))
                .hasSize(2)
                .allMatch(s -> s.getTaskId().equals(7L));
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

    private StudySession newSession(User user, int minutes) {
        StudySession s = new StudySession();
        s.setUser(user);
        s.setMaterialName("Test material");
        s.setDurationMinutes(minutes);
        s.setTimerMode("study");
        s.setCompleted(true);
        LocalDateTime now = LocalDateTime.of(2026, 5, 15, 10, 0);
        s.setCompletedAt(now);
        s.setStudyDate(LocalDate.of(2026, 5, 15));
        return s;
    }
}
