package StudySyncer;

import StudySyncer.dto.DigestContent;
import StudySyncer.entity.Exam;
import StudySyncer.entity.EmailType;
import StudySyncer.entity.Task;
import StudySyncer.entity.TaskStatus;
import StudySyncer.entity.User;
import StudySyncer.repository.EmailSendLogRepository;
import StudySyncer.repository.ExamRepository;
import StudySyncer.repository.TaskRepository;
import StudySyncer.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Dispatcher for Phase 8 emails: morning digest, overdue reminders,
 * exam reminders.
 *
 * Every decision — firing window, idempotency, daily cap — is made
 * per-user with their own timezone. The server's wall clock is NEVER
 * used for user-facing timing.
 *
 * Scheduler cadence:
 *   Digest   — every 15 minutes  (compares now ± 7min against user.digestLocalTime)
 *   Overdue  — every 15 minutes  (same window logic against overdueReminderLocalTime)
 *   Exam     — every hour on the hour  (evaluates daysUntil against {7,3,1})
 *
 * All jobs share this class but run independently — one crashing doesn't
 * crash the others, and per-user exceptions stay contained so a single
 * bad row can't poison a batch.
 *
 * Idempotency: every dispatch path consults {@link EmailSendLogRepository}
 * BEFORE composing the email; a unique constraint on
 * (userId, emailType, referenceId) protects against race conditions.
 *
 * Daily cap: the whole scheduler gates on a 5-email-per-24h count to
 * contain any runaway logic from ever nuking a user's inbox.
 */
@Component
public class NotificationScheduler {

    private static final Logger log = LoggerFactory.getLogger(NotificationScheduler.class);

    /** Spec 8.9: hard per-user cap. */
    static final int DAILY_EMAIL_CAP = 5;

    /** Symmetric slack so a 15-min cron catches an 08:00 setpoint at 07:58. */
    static final Duration FIRING_WINDOW = Duration.ofMinutes(7);

    private static final ZoneId DEFAULT_ZONE = ZoneId.of("America/Toronto");

    private final UserRepository          userRepo;
    private final TaskRepository          taskRepo;
    private final ExamRepository          examRepo;
    private final EmailSendLogRepository  sendLogRepo;
    private final DigestService           digestService;
    private final EmailTemplateService    templates;
    private final EmailService            emailService;

    public NotificationScheduler(UserRepository userRepo,
                                 TaskRepository taskRepo,
                                 ExamRepository examRepo,
                                 EmailSendLogRepository sendLogRepo,
                                 DigestService digestService,
                                 EmailTemplateService templates,
                                 EmailService emailService) {
        this.userRepo      = userRepo;
        this.taskRepo      = taskRepo;
        this.examRepo      = examRepo;
        this.sendLogRepo   = sendLogRepo;
        this.digestService = digestService;
        this.templates     = templates;
        this.emailService  = emailService;
    }

    // ══════════════════════════════════════════════════════════
    //  Job 1: Morning digest — every 15 minutes
    // ══════════════════════════════════════════════════════════

    @Scheduled(cron = "0 */15 * * * *")
    public void runDigestScheduler() {
        log.debug("[NOTIF] Digest tick");
        List<User> candidates = userRepo.findNotificationRecipients();
        for (User user : candidates) {
            if (!user.isDigestEnabled()) continue;
            try {
                dispatchDigestFor(user);
            } catch (Exception e) {
                // Per-user failure isolation — one broken user doesn't abort the batch.
                log.error("[NOTIF] Digest failure for userId={}: {}",
                        user.getId(), e.getMessage(), e);
            }
        }
    }

    private void dispatchDigestFor(User user) {
        ZoneId zone = zoneOf(user);
        ZonedDateTime now = ZonedDateTime.now(zone);
        if (!inFiringWindow(now.toLocalTime(), user.getDigestLocalTime())) return;

        LocalDate today = now.toLocalDate();
        long refId      = EmailService.dateCode(today);

        if (sendLogRepo.existsByUserAndEmailTypeAndReferenceId(user, EmailType.DIGEST, refId)) {
            return;   // already delivered — silent skip
        }
        if (isOverDailyCap(user)) {
            log.warn("[NOTIF] Digest skipped — daily cap hit userId={}", user.getId());
            return;
        }

        DigestContent content = digestService.buildDigest(user, today);
        if (!content.isShouldSend()) {
            // Record a "we checked, nothing to say" row so we don't re-evaluate
            // the full query every 15 minutes for the rest of today.
            recordSuppressed(user, EmailType.DIGEST, refId, "empty-digest");
            return;
        }

        String subject = templates.subjectForDigest(content);
        String html    = templates.renderDigestHtml(user, content);
        String text    = templates.renderDigestPlainText(user, content);
        emailService.sendNotification(user, EmailType.DIGEST, refId, subject, html, text);
    }

    // ══════════════════════════════════════════════════════════
    //  Job 2: Overdue reminder — every 15 minutes
    // ══════════════════════════════════════════════════════════

    @Scheduled(cron = "0 */15 * * * *")
    public void runOverdueReminderScheduler() {
        log.debug("[NOTIF] Overdue tick");
        List<User> candidates = userRepo.findNotificationRecipients();
        for (User user : candidates) {
            if (!user.isOverdueReminderEnabled()) continue;
            try {
                dispatchOverdueFor(user);
            } catch (Exception e) {
                log.error("[NOTIF] Overdue failure for userId={}: {}",
                        user.getId(), e.getMessage(), e);
            }
        }
    }

    private void dispatchOverdueFor(User user) {
        ZoneId zone = zoneOf(user);
        ZonedDateTime now = ZonedDateTime.now(zone);
        if (!inFiringWindow(now.toLocalTime(), user.getOverdueReminderLocalTime())) return;

        LocalDate today     = now.toLocalDate();
        LocalDate yesterday = today.minusDays(1);
        long refId          = EmailService.dateCode(today);

        if (sendLogRepo.existsByUserAndEmailTypeAndReferenceId(user, EmailType.OVERDUE_REMINDER, refId)) {
            return;
        }
        if (isOverDailyCap(user)) {
            log.warn("[NOTIF] Overdue skipped — daily cap hit userId={}", user.getId());
            return;
        }

        List<Task> overdue = taskRepo.findByUserAndDueDateBeforeAndStatusNot(
                user, today, TaskStatus.COMPLETED);

        // Spec 8.4 anti-fatigue rule: only fire if at least ONE task went
        // overdue yesterday specifically. If the user has been chronically
        // behind for days, don't nag them again — they know.
        boolean hasFreshOverdue = overdue.stream()
                .anyMatch(t -> yesterday.equals(t.getDueDate()));
        if (overdue.isEmpty() || !hasFreshOverdue) {
            recordSuppressed(user, EmailType.OVERDUE_REMINDER, refId,
                    overdue.isEmpty() ? "no-overdue" : "no-fresh-overdue");
            return;
        }

        String subject = templates.subjectForOverdue(overdue.size());
        String html    = templates.renderOverdueHtml(user, overdue, today);
        String text    = templates.renderOverduePlainText(user, overdue, today);
        emailService.sendNotification(user, EmailType.OVERDUE_REMINDER, refId, subject, html, text);
    }

    // ══════════════════════════════════════════════════════════
    //  Job 3: Exam reminder — every hour on the hour
    // ══════════════════════════════════════════════════════════

    @Scheduled(cron = "0 0 * * * *")
    public void runExamReminderScheduler() {
        log.debug("[NOTIF] Exam reminder tick");
        List<User> candidates = userRepo.findNotificationRecipients();
        for (User user : candidates) {
            if (!user.isExamReminderEnabled()) continue;
            try {
                dispatchExamRemindersFor(user);
            } catch (Exception e) {
                log.error("[NOTIF] Exam-reminder failure for userId={}: {}",
                        user.getId(), e.getMessage(), e);
            }
        }
    }

    private void dispatchExamRemindersFor(User user) {
        ZoneId zone = zoneOf(user);
        LocalDate today = LocalDate.now(zone);
        LocalDateTime nowLocal = LocalDateTime.now(zone);

        // Pull future exams only — past exams never fire reminders.
        List<Exam> futureExams = examRepo.findByUserAndDateTimeAfterOrderByDateTimeAsc(user, nowLocal);
        for (Exam exam : futureExams) {
            LocalDate examDate = exam.getDateTime().toLocalDate();
            int daysUntil = (int) ChronoUnit.DAYS.between(today, examDate);

            EmailType type;
            switch (daysUntil) {
                case 7: type = EmailType.EXAM_REMINDER_7D; break;
                case 3: type = EmailType.EXAM_REMINDER_3D; break;
                case 1: type = EmailType.EXAM_REMINDER_1D; break;
                default: continue;   // not a threshold — skip silently
            }

            Long refId = exam.getId();
            if (sendLogRepo.existsByUserAndEmailTypeAndReferenceId(user, type, refId)) continue;
            if (isOverDailyCap(user)) {
                log.warn("[NOTIF] Exam reminder skipped — daily cap hit userId={} examId={}",
                        user.getId(), exam.getId());
                continue;
            }

            String subject = templates.subjectForExamReminder(exam, daysUntil);
            String html    = templates.renderExamReminderHtml(user, exam, daysUntil, type);
            String text    = templates.renderExamReminderPlainText(user, exam, daysUntil, type);
            emailService.sendNotification(user, type, refId, subject, html, text);
        }
    }

    // ══════════════════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════════════════

    /**
     * True if {@code now} is within {@link #FIRING_WINDOW} of the user's
     * configured setpoint. Symmetric so 08:00 catches ticks at 07:55, 08:00,
     * and 08:05 all the same day (cron runs every 15 min, so we see at most
     * one in-window tick per setpoint).
     *
     * Minute-precision only — ignores seconds/nanos to match what the user
     * enters in the &lt;input type="time"&gt; control.
     */
    static boolean inFiringWindow(LocalTime now, LocalTime setpoint) {
        if (now == null || setpoint == null) return false;
        long nowMin   = now.toSecondOfDay()      / 60;
        long pointMin = setpoint.toSecondOfDay() / 60;
        long windowMin = FIRING_WINDOW.toMinutes();
        long diff = Math.abs(nowMin - pointMin);
        // Wrap-around: midnight boundary (23:58 setpoint, 00:02 tick).
        long wrap = 24 * 60 - diff;
        return Math.min(diff, wrap) <= windowMin;
    }

    /**
     * 5-email-per-24h cap (spec 8.9). Counts ALL email types — digest,
     * overdue, exam, and retrofitted goal emails — because the damage
     * any bug can do is the sum across types.
     */
    private boolean isOverDailyCap(User user) {
        Instant since = Instant.now().minus(24, ChronoUnit.HOURS);
        long count = sendLogRepo.countByUserAndSentAtAfter(user, since);
        return count >= DAILY_EMAIL_CAP;
    }

    /**
     * Writes a "checked, nothing to send" send-log row so the 15-minute
     * scheduler doesn't re-evaluate the same user all day. referenceId
     * collision is benign (same as a successful send) — the unique
     * constraint silently no-ops a duplicate.
     */
    private void recordSuppressed(User user, EmailType type, long refId, String reason) {
        try {
            StudySyncer.entity.EmailSendLog row = new StudySyncer.entity.EmailSendLog();
            row.setUser(user);
            row.setEmailType(type);
            row.setReferenceId(refId);
            sendLogRepo.save(row);
            log.debug("[NOTIF] Suppressed {} userId={} reason={}", type, user.getId(), reason);
        } catch (Exception e) {
            // Race: another thread/tick got there first. Benign.
            log.debug("[NOTIF] Suppress write collided (benign) userId={} type={}: {}",
                    user.getId(), type, e.getMessage());
        }
    }

    private static ZoneId zoneOf(User user) {
        String tz = user.getTimezone();
        if (tz == null || tz.isBlank()) return DEFAULT_ZONE;
        try { return ZoneId.of(tz); }
        catch (Exception e) { return DEFAULT_ZONE; }
    }
}
