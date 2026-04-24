package StudySyncer;

import StudySyncer.dto.ExamRequest;
import StudySyncer.entity.Exam;
import StudySyncer.entity.User;
import StudySyncer.repository.ExamRepository;
import StudySyncer.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for ExamService. Exercises the hybrid date/time
 * resolution (shape A vs shape B), ownership enforcement, and the
 * listNextN cutoff.
 */
@SpringBootTest
@ActiveProfiles("test")
class ExamServiceTest {

    @Autowired private ExamService    examService;
    @Autowired private ExamRepository examRepo;
    @Autowired private UserRepository userRepo;

    private User owner;
    private User stranger;

    @BeforeEach
    void setUp() {
        examRepo.deleteAll();
        userRepo.deleteAll();
        owner    = userRepo.save(buildUser("exam_svc_owner"));
        stranger = userRepo.save(buildUser("exam_svc_stranger"));
    }

    @AfterEach
    void tearDown() {
        examRepo.deleteAll();
        userRepo.deleteAll();
    }

    // ── Create: shape B (separate date + time + timezone) ─────────────

    @Test
    void create_withShapeBFields_persistsLocalDateTime() {
        ExamRequest req = new ExamRequest();
        req.setTitle("Midterm 1");
        req.setCourse("EECS 281");
        req.setDate("2026-06-15");
        req.setTime("14:00");
        req.setTimezone("America/Toronto");
        req.setLocation("Dennis 109");

        Exam saved = examService.create(owner, req);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getTitle()).isEqualTo("Midterm 1");
        assertThat(saved.getCourse()).isEqualTo("EECS 281");
        assertThat(saved.getDateTime()).isEqualTo(LocalDateTime.of(2026, 6, 15, 14, 0));
        assertThat(saved.getLocation()).isEqualTo("Dennis 109");
    }

    // ── Create: shape A (pre-serialized Instant) ─────────────────────

    @Test
    void create_withShapeAInstant_storesInUsersLocalTime() {
        ExamRequest req = new ExamRequest();
        req.setTitle("Final");
        Instant inst = ZonedDateTime.of(2026, 6, 15, 14, 0, 0, 0,
                ZoneId.of("America/Toronto")).toInstant();
        req.setDateTime(inst);
        req.setTimezone("America/Toronto");

        Exam saved = examService.create(owner, req);

        // Stored value is local to the caller's zone (2 PM Toronto wall-clock).
        assertThat(saved.getDateTime()).isEqualTo(LocalDateTime.of(2026, 6, 15, 14, 0));
    }

    // ── Create: missing dateTime (both shapes absent) → 400 ─────────

    @Test
    void create_withoutDateTime_throwsIllegalArgument() {
        ExamRequest req = new ExamRequest();
        req.setTitle("No date");

        assertThatThrownBy(() -> examService.create(owner, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("date and time");
    }

    // ── Ownership ────────────────────────────────────────────────────

    @Test
    void get_anotherUsersExam_throwsNotFound() {
        Exam e = examRepo.save(buildExam(owner, "Private",
                LocalDateTime.of(2026, 6, 15, 14, 0)));

        assertThatThrownBy(() -> examService.get(stranger, e.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void update_anotherUsersExam_throwsNotFound() {
        Exam e = examRepo.save(buildExam(owner, "Private",
                LocalDateTime.of(2026, 6, 15, 14, 0)));
        ExamRequest req = new ExamRequest();
        req.setTitle("Hack");
        req.setDate("2026-06-15");
        req.setTime("14:00");

        assertThatThrownBy(() -> examService.update(stranger, e.getId(), req))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void delete_anotherUsersExam_throwsNotFound() {
        Exam e = examRepo.save(buildExam(owner, "Private",
                LocalDateTime.of(2026, 6, 15, 14, 0)));

        assertThatThrownBy(() -> examService.delete(stranger, e.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(examRepo.findById(e.getId())).isPresent();
    }

    // ── listNextN: excludes past, limits to N ────────────────────────

    @Test
    void listNextN_limitsAndExcludesPast() {
        // One past, four future exams.
        examRepo.save(buildExam(owner, "Past final",
                LocalDateTime.now(ZoneId.of("America/Toronto")).minusDays(7)));
        LocalDateTime future = LocalDateTime.now(ZoneId.of("America/Toronto")).plusDays(1);
        examRepo.save(buildExam(owner, "Soonest",      future));
        examRepo.save(buildExam(owner, "Second",       future.plusDays(2)));
        examRepo.save(buildExam(owner, "Third",        future.plusDays(5)));
        examRepo.save(buildExam(owner, "Fourth-later", future.plusDays(10)));

        var top3 = examService.listNextN(owner, 3);
        assertThat(top3)
                .hasSize(3)
                .extracting(Exam::getTitle)
                .containsExactly("Soonest", "Second", "Third");
    }

    // ── listForUser: upcoming vs past respects tz ─────────────────

    @Test
    void listForUser_upcomingFilterIncludesTodayInLocalZone() {
        ZoneId tz = ZoneId.of("America/Toronto");
        // Same day, a few hours later — must show in "upcoming".
        LocalDateTime laterToday = LocalDateTime.now(tz).plusHours(2);
        examRepo.save(buildExam(owner, "Later today", laterToday));
        // Yesterday — must be excluded from "upcoming".
        examRepo.save(buildExam(owner, "Yesterday",   LocalDateTime.now(tz).minusDays(1)));

        var upcoming = examService.listForUser(owner, tz, "upcoming");
        assertThat(upcoming).extracting(Exam::getTitle).containsExactly("Later today");
    }

    // ── Helpers ──────────────────────────────────────────────────────

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
