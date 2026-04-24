package StudySyncer;

import StudySyncer.entity.EmailType;
import StudySyncer.entity.Exam;
import StudySyncer.entity.Task;
import StudySyncer.entity.TaskStatus;
import StudySyncer.entity.User;
import StudySyncer.repository.EmailSendLogRepository;
import StudySyncer.repository.ExamRepository;
import StudySyncer.repository.TaskRepository;
import StudySyncer.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Critical scheduler tests (spec 8.11). Uses a real Spring context so the
 * JPA side (EmailSendLog unique constraint, per-user tz resolution) is
 * exercised end-to-end, but mocks {@link EmailService} so no real SMTP
 * traffic ever leaves the test JVM.
 *
 * Timing is tricky in unit tests — where possible we drive the scheduler's
 * pure-function helpers ({@code inFiringWindow}) directly rather than
 * stubbing the system clock.
 */
@SpringBootTest
@ActiveProfiles("test")
class NotificationSchedulerTest {

    @Autowired private UserRepository          userRepo;
    @Autowired private TaskRepository          taskRepo;
    @Autowired private ExamRepository          examRepo;
    @Autowired private EmailSendLogRepository  sendLogRepo;
    @Autowired private NotificationScheduler   scheduler;

    // Stub the dispatch surface so we can assert call counts without any
    // real network traffic. The send-log writer is in EmailService, so
    // stubbing it means the scheduler's idempotency path is tested purely
    // via the explicit sendLogRepo.save() calls (e.g. suppression rows).
    @MockBean private EmailService             emailService;

    @BeforeEach
    void setUp() {
        sendLogRepo.deleteAll();
        examRepo.deleteAll();
        taskRepo.deleteAll();
        userRepo.deleteAll();

        // Stub: treat every send as successful. Individual tests can override.
        when(emailService.sendNotification(any(), any(), any(), anyString(), anyString(), anyString()))
                .thenAnswer(inv -> {
                    // Simulate EmailService writing the send-log on success, so
                    // the scheduler's own "already sent today" guard works on
                    // subsequent ticks within the same test.
                    User u = inv.getArgument(0, User.class);
                    EmailType t = inv.getArgument(1, EmailType.class);
                    Long ref = inv.getArgument(2, Long.class);
                    StudySyncer.entity.EmailSendLog row = new StudySyncer.entity.EmailSendLog();
                    row.setUser(u);
                    row.setEmailType(t);
                    row.setReferenceId(ref);
                    sendLogRepo.save(row);
                    return true;
                });
    }

    @AfterEach
    void tearDown() {
        sendLogRepo.deleteAll();
        examRepo.deleteAll();
        taskRepo.deleteAll();
        userRepo.deleteAll();
    }

    // ── Firing-window pure helper ──────────────────────────────

    @Test
    void inFiringWindow_matchesSetpointWithinSevenMinutes() {
        // Exact match, and ± 7 min should pass.
        assertThat(NotificationScheduler.inFiringWindow(
                LocalTime.of(8, 0), LocalTime.of(8, 0))).isTrue();
        assertThat(NotificationScheduler.inFiringWindow(
                LocalTime.of(8, 5), LocalTime.of(8, 0))).isTrue();
        assertThat(NotificationScheduler.inFiringWindow(
                LocalTime.of(7, 55), LocalTime.of(8, 0))).isTrue();

        // Outside the window.
        assertThat(NotificationScheduler.inFiringWindow(
                LocalTime.of(8, 8), LocalTime.of(8, 0))).isFalse();
        assertThat(NotificationScheduler.inFiringWindow(
                LocalTime.of(9, 0), LocalTime.of(8, 0))).isFalse();
    }

    @Test
    void inFiringWindow_handlesMidnightWrap() {
        // 23:58 setpoint, 00:02 tick → 4 minutes apart across midnight.
        assertThat(NotificationScheduler.inFiringWindow(
                LocalTime.of(0, 2), LocalTime.of(23, 58))).isTrue();
    }

    // ── Digest sender: sends once per user per day ────────────

    @Test
    void digestScheduler_fires_onlyOncePerDayEvenAcrossRepeatedTicks() {
        // Configure the user to want digest at the current Toronto local time,
        // so the scheduler's window check will pass regardless of when CI runs.
        ZoneId tz = ZoneId.of("America/Toronto");
        LocalTime nowLocal = ZonedDateTime.now(tz).toLocalTime().withSecond(0).withNano(0);

        User user = userRepo.save(digestUser("sched_user", tz.getId(), nowLocal));
        taskRepo.save(task(user, "Due today", LocalDate.now(tz), TaskStatus.NOT_STARTED));

        // First tick — sends.
        scheduler.runDigestScheduler();
        verify(emailService, times(1)).sendNotification(
                any(User.class), eq(EmailType.DIGEST), anyLong(),
                anyString(), anyString(), anyString());

        // Second tick 15 min later — the send-log row from the first tick
        // must short-circuit a duplicate send.
        scheduler.runDigestScheduler();
        verify(emailService, times(1)).sendNotification(
                any(User.class), eq(EmailType.DIGEST), anyLong(),
                anyString(), anyString(), anyString());
    }

    // ── Skip when disabled ─────────────────────────────────────

    @Test
    void digestScheduler_skipsUsersWithDigestDisabled() {
        ZoneId tz = ZoneId.of("America/Toronto");
        LocalTime nowLocal = ZonedDateTime.now(tz).toLocalTime().withSecond(0).withNano(0);

        User user = digestUser("sched_off", tz.getId(), nowLocal);
        user.setDigestEnabled(false);
        userRepo.save(user);
        taskRepo.save(task(user, "Due today", LocalDate.now(tz), TaskStatus.NOT_STARTED));

        scheduler.runDigestScheduler();
        verify(emailService, never()).sendNotification(
                any(), any(), any(), anyString(), anyString(), anyString());
    }

    // ── Skip when no accountability email ─────────────────────

    @Test
    void digestScheduler_skipsUsersWithoutAccountabilityEmail() {
        ZoneId tz = ZoneId.of("America/Toronto");
        LocalTime nowLocal = ZonedDateTime.now(tz).toLocalTime().withSecond(0).withNano(0);

        User user = digestUser("sched_no_email", tz.getId(), nowLocal);
        user.setAccountabilityEmail(null);
        userRepo.save(user);

        scheduler.runDigestScheduler();
        verify(emailService, never()).sendNotification(
                any(), any(), any(), anyString(), anyString(), anyString());
    }

    // ── Empty digest is suppressed once; skipped quietly thereafter ─

    @Test
    void digestScheduler_emptyDigestRecordsSuppressedRow() {
        ZoneId tz = ZoneId.of("America/Toronto");
        LocalTime nowLocal = ZonedDateTime.now(tz).toLocalTime().withSecond(0).withNano(0);
        User user = userRepo.save(digestUser("sched_empty", tz.getId(), nowLocal));
        // No tasks, no exams → shouldSend=false.

        scheduler.runDigestScheduler();
        verify(emailService, never()).sendNotification(
                any(), any(), any(), anyString(), anyString(), anyString());

        // Scheduler wrote a suppression row. Subsequent ticks re-check
        // existence first and exit silently.
        assertThat(sendLogRepo.findAll())
                .extracting(l -> l.getEmailType())
                .contains(EmailType.DIGEST);

        scheduler.runDigestScheduler();   // would re-fire without the row
        verify(emailService, never()).sendNotification(
                any(), any(), any(), anyString(), anyString(), anyString());
    }

    // ── Daily-cap guard ────────────────────────────────────────

    @Test
    void dailyCap_blocksSixthSendInOneDay() {
        ZoneId tz = ZoneId.of("America/Toronto");
        LocalTime nowLocal = ZonedDateTime.now(tz).toLocalTime().withSecond(0).withNano(0);
        User user = userRepo.save(digestUser("sched_cap", tz.getId(), nowLocal));
        taskRepo.save(task(user, "Due today", LocalDate.now(tz), TaskStatus.NOT_STARTED));

        // Pre-seed 5 "sent today" rows of various types to hit the cap
        // WITHOUT using the DIGEST key (so the digest's own idempotency
        // check doesn't short-circuit first).
        EmailType[] filler = {
                EmailType.OVERDUE_REMINDER, EmailType.EXAM_REMINDER_7D,
                EmailType.EXAM_REMINDER_3D, EmailType.EXAM_REMINDER_1D,
                EmailType.GOAL_REACHED
        };
        for (int i = 0; i < filler.length; i++) {
            StudySyncer.entity.EmailSendLog row = new StudySyncer.entity.EmailSendLog();
            row.setUser(user);
            row.setEmailType(filler[i]);
            row.setReferenceId(100L + i);   // arbitrary unique refIds
            sendLogRepo.save(row);
        }

        scheduler.runDigestScheduler();
        // Cap triggered — no digest send.
        verify(emailService, never()).sendNotification(
                any(), any(), any(), anyString(), anyString(), anyString());
    }

    // ── Overdue: anti-fatigue ─ fires only when something just went overdue ─

    @Test
    void overdueScheduler_skipsWhenAllOverdueTasksAreAncient() {
        ZoneId tz = ZoneId.of("America/Toronto");
        LocalTime nowLocal = ZonedDateTime.now(tz).toLocalTime().withSecond(0).withNano(0);
        User user = overdueUser("sched_old", tz.getId(), nowLocal);
        userRepo.save(user);
        // Every overdue task is from 3+ days ago — no "freshly overdue yesterday".
        taskRepo.save(task(user, "Week-old lab",   LocalDate.now(tz).minusDays(7), TaskStatus.NOT_STARTED));
        taskRepo.save(task(user, "Three-day hw",   LocalDate.now(tz).minusDays(3), TaskStatus.IN_PROGRESS));

        scheduler.runOverdueReminderScheduler();
        verify(emailService, never()).sendNotification(
                any(), any(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void overdueScheduler_firesWhenATaskWentOverdueYesterday() {
        ZoneId tz = ZoneId.of("America/Toronto");
        LocalTime nowLocal = ZonedDateTime.now(tz).toLocalTime().withSecond(0).withNano(0);
        User user = userRepo.save(overdueUser("sched_fresh", tz.getId(), nowLocal));
        taskRepo.save(task(user, "Missed yesterday",
                LocalDate.now(tz).minusDays(1), TaskStatus.NOT_STARTED));

        scheduler.runOverdueReminderScheduler();
        verify(emailService, atLeastOnce()).sendNotification(
                any(), eq(EmailType.OVERDUE_REMINDER), anyLong(),
                anyString(), anyString(), anyString());
    }

    // ── Exam reminder threshold hit ───────────────────────────

    @Test
    void examScheduler_firesOnlyAtSevenThreeOneDayMark() {
        ZoneId tz = ZoneId.of("America/Toronto");
        User user = userRepo.save(examUser("sched_exam", tz.getId()));

        // Exam exactly 7 days out at user-local time.
        LocalDateTime sevenDays = LocalDateTime.now(tz)
                .plusDays(7).withSecond(0).withNano(0);
        Exam exam = new Exam();
        exam.setUser(user);
        exam.setTitle("Midterm");
        exam.setDateTime(sevenDays);
        examRepo.save(exam);

        scheduler.runExamReminderScheduler();
        verify(emailService).sendNotification(
                any(), eq(EmailType.EXAM_REMINDER_7D), eq(exam.getId()),
                anyString(), anyString(), anyString());

        // Re-run the scheduler — existing send-log row keeps it idempotent.
        scheduler.runExamReminderScheduler();
        verify(emailService, times(1)).sendNotification(
                any(), eq(EmailType.EXAM_REMINDER_7D), eq(exam.getId()),
                anyString(), anyString(), anyString());
    }

    // ── Helpers ────────────────────────────────────────────────

    private User digestUser(String username, String tzId, LocalTime digestAt) {
        User u = baseUser(username, tzId);
        u.setDigestEnabled(true);
        u.setDigestLocalTime(digestAt);
        return u;
    }

    private User overdueUser(String username, String tzId, LocalTime overdueAt) {
        User u = baseUser(username, tzId);
        u.setOverdueReminderEnabled(true);
        u.setOverdueReminderLocalTime(overdueAt);
        return u;
    }

    private User examUser(String username, String tzId) {
        User u = baseUser(username, tzId);
        u.setExamReminderEnabled(true);
        return u;
    }

    private User baseUser(String username, String tzId) {
        User u = new User();
        u.setUsername(username);
        u.setEmail(username + "@test.example");
        u.setPasswordHash("$2a$10$placeholder_hash_value_for_tests");
        u.setEmailVerified(true);
        u.setAccountabilityEmail(username + "-acct@test.example");
        u.setTimezone(tzId);
        return u;
    }

    private Task task(User user, String title, LocalDate due, TaskStatus status) {
        Task t = new Task();
        t.setUser(user);
        t.setTitle(title);
        t.setDueDate(due);
        t.setStatus(status);
        return t;
    }
}
