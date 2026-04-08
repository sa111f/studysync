package StudySyncer;

import StudySyncer.dto.*;
import StudySyncer.entity.DailyGoal;
import StudySyncer.entity.StudySession;
import StudySyncer.entity.User;
import StudySyncer.repository.StudySessionRepository;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.TextStyle;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class TrackerService {

    private final StudySessionRepository sessionRepo;
    private final DailyGoalService       dailyGoalService;

    public TrackerService(StudySessionRepository sessionRepo, DailyGoalService dailyGoalService) {
        this.sessionRepo      = sessionRepo;
        this.dailyGoalService = dailyGoalService;
    }

    // ── Save ──────────────────────────────────────────────

    public void saveSession(User user, String materialName, int durationMinutes,
                             String timerMode, boolean completed) {
        StudySession s = new StudySession();
        s.setUser(user);
        s.setMaterialName((materialName != null && !materialName.isBlank())
                ? materialName : "General");
        s.setDurationMinutes(durationMinutes);
        s.setTimerMode(timerMode);
        s.setCompleted(completed);
        LocalDateTime now = LocalDateTime.now();
        s.setCompletedAt(now);
        s.setStartedAt(now.minusMinutes(durationMinutes));
        s.setStudyDate(now.toLocalDate());
        sessionRepo.save(s);

        // Step 1: sync today's goal progress inside its own @Transactional.
        // addCompletedMinutes commits before returning, so the DB is up-to-date.
        DailyGoal updatedGoal = dailyGoalService.addCompletedMinutes(user, durationMinutes);

        // Step 2: check whether the goal threshold was just crossed and send
        // the goal-reached email if needed.  Called AFTER the transaction commits
        // so the email fires outside the DB transaction — no open connection during
        // the Resend HTTP call, and no rollback risk on email failure.
        dailyGoalService.triggerGoalReachedEmailIfNeeded(updatedGoal);
    }

    // ── Date range ────────────────────────────────────────

    /** Returns [from, to] inclusive dates for the given range + offset. */
    public LocalDate[] getDateRange(String range, int offset) {
        LocalDate today = LocalDate.now();
        LocalDate from, to;
        switch (range) {
            case "today": {
                LocalDate d = today.plusDays(offset);
                from = d;
                to   = d;
                break;
            }
            case "month": {
                LocalDate base = today.withDayOfMonth(1).plusMonths(offset);
                from = base;
                to   = base.with(TemporalAdjusters.lastDayOfMonth());
                break;
            }
            case "year": {
                int year = today.getYear() + offset;
                from = LocalDate.of(year, 1, 1);
                to   = LocalDate.of(year, 12, 31);
                break;
            }
            default: { // "week"
                LocalDate monday = today
                        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                        .plusWeeks(offset);
                from = monday;
                to   = monday.plusDays(6);
                break;
            }
        }
        return new LocalDate[]{from, to};
    }

    /** Human-readable label for the period shown in the UI. */
    public String getPeriodLabel(String range, int offset) {
        LocalDate[] r = getDateRange(range, offset);
        LocalDate from = r[0];
        switch (range) {
            case "today":
                if (offset == 0)  return "Today";
                if (offset == -1) return "Yesterday";
                return from.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
                        + " " + from.getDayOfMonth();
            case "week":
                if (offset == 0)  return "This Week";
                if (offset == -1) return "Last Week";
                return from.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
                        + " " + from.getDayOfMonth()
                        + " – " + r[1].getDayOfMonth();
            case "month":
                String month = from.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
                if (offset == 0) return "This Month (" + month + ")";
                return month + " " + from.getYear();
            case "year":
                if (offset == 0) return "This Year (" + from.getYear() + ")";
                return String.valueOf(from.getYear());
            default:
                return range;
        }
    }

    // ── Summary ───────────────────────────────────────────

    public TrackerSummaryDto getSummary(User user, String range, int offset) {
        LocalDate[] r = getDateRange(range, offset);
        List<StudySession> sessions = sessionRepo
                .findByUserAndStudyDateBetweenOrderByCompletedAtDesc(user, r[0], r[1]);

        int totalMinutes = sessions.stream().mapToInt(StudySession::getDurationMinutes).sum();

        long uniqueDays = sessions.stream()
                .map(StudySession::getStudyDate)
                .distinct().count();

        TrackerSummaryDto dto = new TrackerSummaryDto();
        dto.setTotalMinutes(totalMinutes);
        dto.setDaysAccessed((int) uniqueDays);
        dto.setStreak(computeStreak(user));
        dto.setPeriodLabel(getPeriodLabel(range, offset));
        return dto;
    }

    /** Consecutive-day streak ending today (or yesterday if user hasn't studied today yet). */
    private int computeStreak(User user) {
        List<StudySession> all = sessionRepo.findByUserOrderByCompletedAtDesc(user);
        if (all.isEmpty()) return 0;

        Set<LocalDate> studyDays = all.stream()
                .map(StudySession::getStudyDate)
                .collect(Collectors.toSet());

        LocalDate check = LocalDate.now();
        if (!studyDays.contains(check)) {
            check = check.minusDays(1);
        }
        if (!studyDays.contains(check)) return 0;

        int streak = 0;
        while (studyDays.contains(check)) {
            streak++;
            check = check.minusDays(1);
        }
        return streak;
    }

    // ── Chart data ────────────────────────────────────────

    public TrackerChartDto getChartData(User user, String range, int offset) {
        LocalDate[] r = getDateRange(range, offset);
        List<StudySession> sessions = sessionRepo
                .findByUserAndStudyDateBetweenOrderByCompletedAtDesc(user, r[0], r[1]);

        // Aggregate minutes per calendar date
        Map<LocalDate, Integer> byDate = new HashMap<>();
        sessions.forEach(s -> byDate.merge(s.getStudyDate(), s.getDurationMinutes(), Integer::sum));

        List<String>  labels = new ArrayList<>();
        List<Integer> values = new ArrayList<>();

        if ("today".equals(range)) {
            // Aggregate minutes by hour of day (0–23) using session completion time
            int[] byHour = new int[24];
            for (StudySession s : sessions) {
                byHour[s.getCompletedAt().getHour()] += s.getDurationMinutes();
            }
            for (int h = 0; h < 24; h++) {
                labels.add(h + "h");
                values.add(byHour[h]);
            }
        } else if ("week".equals(range)) {
            for (int i = 0; i < 7; i++) {
                LocalDate d = r[0].plusDays(i);
                labels.add(d.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH));
                values.add(byDate.getOrDefault(d, 0));
            }
        } else if ("month".equals(range)) {
            long days = r[0].until(r[1]).getDays() + 1;
            for (int i = 0; i < days; i++) {
                LocalDate d = r[0].plusDays(i);
                labels.add(String.valueOf(d.getDayOfMonth()));
                values.add(byDate.getOrDefault(d, 0));
            }
        } else { // year
            for (int m = 1; m <= 12; m++) {
                LocalDate start = LocalDate.of(r[0].getYear(), m, 1);
                LocalDate end   = start.with(TemporalAdjusters.lastDayOfMonth());
                int total = 0;
                for (Map.Entry<LocalDate, Integer> e : byDate.entrySet()) {
                    if (!e.getKey().isBefore(start) && !e.getKey().isAfter(end)) {
                        total += e.getValue();
                    }
                }
                labels.add(Month.of(m).getDisplayName(TextStyle.SHORT, Locale.ENGLISH));
                values.add(total);
            }
        }

        TrackerChartDto dto = new TrackerChartDto();
        dto.setLabels(labels);
        dto.setValues(values);
        dto.setTotalMinutes(values.stream().mapToInt(Integer::intValue).sum());
        dto.setPeriodLabel(getPeriodLabel(range, offset));
        return dto;
    }

    // ── Material breakdown ────────────────────────────────

    public List<TrackerMaterialDto> getMaterialBreakdown(User user, String range, int offset) {
        LocalDate[] r = getDateRange(range, offset);
        List<StudySession> sessions = sessionRepo
                .findByUserAndStudyDateBetweenOrderByCompletedAtDesc(user, r[0], r[1]);

        Map<String, Integer> byMaterial = new LinkedHashMap<>();
        sessions.forEach(s -> byMaterial.merge(s.getMaterialName(), s.getDurationMinutes(), Integer::sum));

        return byMaterial.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .map(e -> new TrackerMaterialDto(e.getKey(), e.getValue()))
                .collect(Collectors.toList());
    }

    // ── Session list ──────────────────────────────────────

    public List<TrackerSessionDto> getSessions(User user, String range, int offset) {
        LocalDate[] r = getDateRange(range, offset);
        List<StudySession> sessions = sessionRepo
                .findByUserAndStudyDateBetweenOrderByCompletedAtDesc(user, r[0], r[1]);

        return sessions.stream()
                .map(s -> new TrackerSessionDto(
                        s.getId(),
                        s.getStudyDate().toString(),
                        s.getCompletedAt().toLocalTime().toString().substring(0, 5),
                        s.getMaterialName(),
                        s.getDurationMinutes(),
                        s.getTimerMode(),
                        s.isCompleted()))
                .collect(Collectors.toList());
    }
}
