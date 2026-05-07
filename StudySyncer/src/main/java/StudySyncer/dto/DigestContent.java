package StudySyncer.dto;

import StudySyncer.entity.Exam;
import StudySyncer.entity.Task;

import java.time.LocalDate;
import java.util.List;

/**
 * Snapshot consumed by {@link StudySyncer.EmailTemplateService} to render
 * the morning digest email. Built by {@link StudySyncer.DigestService} per
 * user per day.
 *
 * {@link #shouldSend} is the "don't spam empty digests" guard — when
 * false, the scheduler still records a send-log row so the check isn't
 * repeated every 15 minutes for the rest of the day.
 */
public final class DigestContent {

    private final LocalDate    userLocalDate;
    private final List<Task>   tasksDueToday;
    private final List<Task>   tasksOverdue;
    private final List<Exam>   examsThisWeek;
    private final int          totalMinutesStudiedYesterday;
    private final int          currentStreak;
    private final boolean      shouldSend;

    public DigestContent(LocalDate userLocalDate,
                         List<Task> tasksDueToday,
                         List<Task> tasksOverdue,
                         List<Exam> examsThisWeek,
                         int totalMinutesStudiedYesterday,
                         int currentStreak,
                         boolean shouldSend) {
        this.userLocalDate                = userLocalDate;
        this.tasksDueToday                = tasksDueToday;
        this.tasksOverdue                 = tasksOverdue;
        this.examsThisWeek                = examsThisWeek;
        this.totalMinutesStudiedYesterday = totalMinutesStudiedYesterday;
        this.currentStreak                = currentStreak;
        this.shouldSend                   = shouldSend;
    }

    public LocalDate  getUserLocalDate()                { return userLocalDate; }
    public List<Task> getTasksDueToday()                { return tasksDueToday; }
    public List<Task> getTasksOverdue()                 { return tasksOverdue; }
    public List<Exam> getExamsThisWeek()                { return examsThisWeek; }
    public int        getTotalMinutesStudiedYesterday() { return totalMinutesStudiedYesterday; }
    public int        getCurrentStreak()                { return currentStreak; }
    public boolean    isShouldSend()                    { return shouldSend; }
}
