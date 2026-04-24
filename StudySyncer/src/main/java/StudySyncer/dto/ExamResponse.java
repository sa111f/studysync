package StudySyncer.dto;

import StudySyncer.entity.Exam;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * Response payload for exam read endpoints. Includes two server-side
 * derived fields so the UI never has to reason about timezones:
 *   - daysUntil : integer days between "today" (user-local) and the exam
 *                 date (user-local). Negative when the exam is past.
 *   - bucket    : coarse categorisation — THIS_WEEK / NEXT_WEEK / LATER /
 *                 PAST — used by /exams section grouping and by the
 *                 Dashboard Next Exams card.
 *
 * Both are computed against the ZoneId passed in. Callers should pass the
 * user's timezone (from User.timezone or the `tz` query param), not UTC.
 */
public class ExamResponse {

    public enum Bucket { THIS_WEEK, NEXT_WEEK, LATER, PAST }

    private Long          id;
    private String        title;
    private String        course;
    private Instant       dateTime;
    private String        material;
    private String        notes;
    private String        location;
    private LocalDateTime createdAt;

    // Derived
    private int     daysUntil;
    private Bucket  bucket;

    public ExamResponse() { /* Jackson */ }

    /**
     * Factory. `today` + `zone` are passed in so a batch list call evaluates
     * every exam against the same "now" — no clock drift mid-loop.
     */
    public static ExamResponse from(Exam e, LocalDate today, ZoneId zone) {
        ExamResponse r = new ExamResponse();
        r.id        = e.getId();
        r.title     = e.getTitle();
        r.course    = e.getCourse();
        r.dateTime  = toInstant(e.getDateTime(), zone);   // entity stores LocalDateTime — assume Toronto wall-clock per project convention
        r.material  = e.getMaterial();
        r.notes     = e.getNotes();
        r.location  = e.getLocation();
        r.createdAt = e.getCreatedAt();

        LocalDate examDate = e.getDateTime() != null
                ? e.getDateTime().toLocalDate()
                : null;

        if (examDate == null) {
            r.daysUntil = 0;
            r.bucket    = Bucket.LATER;
            return r;
        }

        r.daysUntil = (int) ChronoUnit.DAYS.between(today, examDate);
        r.bucket    = bucketFor(examDate, today);
        return r;
    }

    /**
     * Coarse bucket by user-local calendar date.
     *   PAST       → examDate < today
     *   THIS_WEEK  → today .. upcoming Sunday (inclusive)
     *   NEXT_WEEK  → next Monday .. next Sunday (inclusive)
     *   LATER      → beyond next Sunday
     *
     * Week boundary uses ISO (Monday = week start). Spec said "today
     * through end-of-week (Sunday)", so a same-day exam falls in THIS_WEEK
     * rather than PAST regardless of clock time.
     */
    private static Bucket bucketFor(LocalDate examDate, LocalDate today) {
        if (examDate.isBefore(today)) return Bucket.PAST;

        // Upcoming Sunday (inclusive of today itself).
        int daysToSunday = (7 - today.getDayOfWeek().getValue()) % 7;   // Mon=1..Sun=7 → Sun=0
        LocalDate thisSunday = today.plusDays(daysToSunday);
        if (!examDate.isAfter(thisSunday)) return Bucket.THIS_WEEK;

        LocalDate nextSunday = thisSunday.plusDays(7);
        if (!examDate.isAfter(nextSunday)) return Bucket.NEXT_WEEK;

        return Bucket.LATER;
    }

    /**
     * Bridge from the entity's LocalDateTime field to an Instant in the
     * response. The entity was declared LocalDateTime in Phase 1 to match
     * the project convention (User / StudySession / DailyGoal all use
     * LocalDateTime). Treat the stored value as user-local wall-clock
     * time in `zone` and convert.
     *
     * Pre-Phase-6 flag noted in the Phase 1 report called this out as a
     * potential future issue for cross-timezone scheduling — for now we
     * assume the write-side (controller) consistently persists the user's
     * local wall-clock value, which we reconvert here.
     */
    private static Instant toInstant(LocalDateTime local, ZoneId zone) {
        if (local == null) return null;
        return local.atZone(zone != null ? zone : ZoneId.of("UTC")).toInstant();
    }

    // ── Getters ───────────────────────────────────────────
    public Long          getId()        { return id; }
    public String        getTitle()     { return title; }
    public String        getCourse()    { return course; }
    public Instant       getDateTime()  { return dateTime; }
    public String        getMaterial()  { return material; }
    public String        getNotes()     { return notes; }
    public String        getLocation()  { return location; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public int           getDaysUntil() { return daysUntil; }
    public Bucket        getBucket()    { return bucket; }
}
