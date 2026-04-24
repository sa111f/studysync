package StudySyncer;

import StudySyncer.dto.DigestContent;
import StudySyncer.entity.Exam;
import StudySyncer.entity.StudySession;
import StudySyncer.entity.Task;
import StudySyncer.entity.TaskStatus;
import StudySyncer.entity.User;
import StudySyncer.repository.ExamRepository;
import StudySyncer.repository.StudySessionRepository;
import StudySyncer.repository.TaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds a per-user morning {@link DigestContent} snapshot (spec 8.3).
 *
 * The service is intentionally pure-read — it NEVER writes. The scheduler
 * decides whether to actually dispatch.
 *
 * "This week" window for exams: today through the upcoming Sunday, matching
 * the Exam.bucket "THIS_WEEK" computation in ExamResponse. Using one shared
 * concept keeps the email and the homepage dashboard in agreement.
 */
@Service
public class DigestService {

    private final TaskRepository         taskRepo;
    private final ExamRepository         examRepo;
    private final StudySessionRepository sessionRepo;

    public DigestService(TaskRepository taskRepo,
                         ExamRepository examRepo,
                         StudySessionRepository sessionRepo) {
        this.taskRepo    = taskRepo;
        this.examRepo    = examRepo;
        this.sessionRepo = sessionRepo;
    }

    /**
     * Build the digest for {@code user} as of {@code today} (user-local
     * calendar date). The scheduler resolves "today" once per user and
     * passes it in — no repeat LocalDate.now(zone) calls inside the loop.
     */
    @Transactional(readOnly = true)
    public DigestContent buildDigest(User user, LocalDate today) {
        List<Task> todayActive   = activeTasksDueOn(user, today);
        List<Task> overdueActive = taskRepo.findByUserAndDueDateBeforeAndStatusNot(
                user, today, TaskStatus.COMPLETED);
        List<Exam> thisWeekExams = examsThisWeek(user, today);

        int yesterdayMinutes = (int) sessionRepo.sumDurationByUserAndDate(user, today.minusDays(1));
        int streak           = computeStreak(user, today);

        boolean shouldSend = !todayActive.isEmpty()
                          || !overdueActive.isEmpty()
                          || !thisWeekExams.isEmpty();

        return new DigestContent(today, todayActive, overdueActive, thisWeekExams,
                yesterdayMinutes, streak, shouldSend);
    }

    // ── Internals ─────────────────────────────────────────────────────

    /**
     * Tasks whose dueDate == today AND status != COMPLETED, sorted by
     * priority DESC then createdAt ASC so the most urgent item bubbles to
     * the top of the email bullet list.
     */
    private List<Task> activeTasksDueOn(User user, LocalDate date) {
        return taskRepo.findByUserAndDueDateBetween(user, date, date).stream()
                .filter(t -> t.getStatus() != TaskStatus.COMPLETED)
                .sorted((a, b) -> {
                    // HIGH > MEDIUM > LOW. Enum ordinal is LOW=0, MEDIUM=1, HIGH=2.
                    int p = Integer.compare(b.getPriority().ordinal(), a.getPriority().ordinal());
                    if (p != 0) return p;
                    return a.getCreatedAt().compareTo(b.getCreatedAt());
                })
                .toList();
    }

    /**
     * Exams with local date in [today, upcoming-Sunday inclusive].
     * Matches {@code ExamResponse.bucketFor()} so the digest and the
     * homepage dashboard agree on which exams count as "this week".
     */
    private List<Exam> examsThisWeek(User user, LocalDate today) {
        int daysToSunday     = (7 - today.getDayOfWeek().getValue()) % 7;   // Mon=1..Sun=7 → Sun=0
        LocalDate thisSunday = today.plusDays(daysToSunday);
        LocalDateTime from   = today.atStartOfDay();
        LocalDateTime to     = thisSunday.atTime(23, 59, 59);

        return examRepo.findByUserAndDateTimeBetween(user, from, to);
    }

    /**
     * Consecutive-day study streak ending at {@code today} (or {@code today-1}
     * if nothing was studied today yet). Mirrors TrackerService.computeStreak
     * but uses the user's timezone for the calendar boundary.
     */
    private int computeStreak(User user, LocalDate today) {
        List<StudySession> all = sessionRepo.findByUserOrderByCompletedAtDesc(user);
        if (all.isEmpty()) return 0;

        Set<LocalDate> days = all.stream()
                .map(StudySession::getStudyDate)
                .collect(Collectors.toSet());

        LocalDate check = today;
        if (!days.contains(check)) check = check.minusDays(1);
        if (!days.contains(check)) return 0;

        int streak = 0;
        while (days.contains(check)) {
            streak++;
            check = check.minusDays(1);
        }
        return streak;
    }
}
