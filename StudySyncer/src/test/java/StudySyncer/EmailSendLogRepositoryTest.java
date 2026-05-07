package StudySyncer;

import StudySyncer.entity.EmailSendLog;
import StudySyncer.entity.EmailType;
import StudySyncer.entity.User;
import StudySyncer.repository.EmailSendLogRepository;
import StudySyncer.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class EmailSendLogRepositoryTest {

    @Autowired private EmailSendLogRepository sendLogRepo;
    @Autowired private UserRepository         userRepo;

    private User user;

    @BeforeEach
    void setUp() {
        sendLogRepo.deleteAll();
        userRepo.deleteAll();
        user = userRepo.save(buildUser("sendlog_user"));
    }

    @AfterEach
    void tearDown() {
        sendLogRepo.deleteAll();
        userRepo.deleteAll();
    }

    // ── Idempotency: the unique constraint blocks duplicate inserts ─

    @Test
    void save_duplicateUserTypeRefId_violatesUniqueConstraint() {
        sendLogRepo.saveAndFlush(row(user, EmailType.DIGEST, 20260501L));
        assertThatThrownBy(() -> sendLogRepo.saveAndFlush(row(user, EmailType.DIGEST, 20260501L)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ── Same referenceId is fine across different types ────────────

    @Test
    void save_sameRefIdDifferentType_coexists() {
        sendLogRepo.saveAndFlush(row(user, EmailType.DIGEST,           20260501L));
        sendLogRepo.saveAndFlush(row(user, EmailType.OVERDUE_REMINDER, 20260501L));
        assertThat(sendLogRepo.findAll()).hasSize(2);
    }

    // ── existsBy query used by the scheduler's idempotency check ──

    @Test
    void existsByUserAndEmailTypeAndReferenceId_findsMatchingRow() {
        sendLogRepo.save(row(user, EmailType.EXAM_REMINDER_7D, 999L));
        assertThat(sendLogRepo.existsByUserAndEmailTypeAndReferenceId(
                user, EmailType.EXAM_REMINDER_7D, 999L)).isTrue();
        assertThat(sendLogRepo.existsByUserAndEmailTypeAndReferenceId(
                user, EmailType.EXAM_REMINDER_7D, 1000L)).isFalse();
    }

    // ── 24h cap counter ─────────────────────────────────────────────

    @Test
    void countByUserAndSentAtAfter_countsOnlyWithinWindow() {
        EmailSendLog old = row(user, EmailType.DIGEST, 20260401L);
        old.setSentAt(Instant.now().minus(48, ChronoUnit.HOURS));
        sendLogRepo.save(old);

        sendLogRepo.save(row(user, EmailType.OVERDUE_REMINDER, 20260429L));
        sendLogRepo.save(row(user, EmailType.EXAM_REMINDER_7D, 42L));

        Instant cutoff = Instant.now().minus(24, ChronoUnit.HOURS);
        assertThat(sendLogRepo.countByUserAndSentAtAfter(user, cutoff)).isEqualTo(2);
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private EmailSendLog row(User u, EmailType t, long refId) {
        EmailSendLog r = new EmailSendLog();
        r.setUser(u);
        r.setEmailType(t);
        r.setReferenceId(refId);
        return r;
    }

    private User buildUser(String username) {
        User u = new User();
        u.setUsername(username);
        u.setEmail(username + "@test.example");
        u.setPasswordHash("$2a$10$placeholder_hash_value_for_tests");
        u.setEmailVerified(true);
        return u;
    }
}
