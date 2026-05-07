package StudySyncer;

import StudySyncer.dto.ExamRequest;
import StudySyncer.entity.Exam;
import StudySyncer.entity.User;
import StudySyncer.repository.ExamRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;

/**
 * Exam CRUD + list helpers. Scoped to the authenticated user — every
 * read/write funnels through a single ownership check so a controller
 * never needs to re-filter.
 *
 * Datetime strategy: controllers resolve the hybrid {@link ExamRequest}
 * shape (combined ISO vs. date+time+tz strings) and hand a single
 * LocalDateTime to the service. Storing LocalDateTime matches the Phase 1
 * entity definition and the project convention.
 */
@Service
public class ExamService {

    private final ExamRepository examRepo;

    public ExamService(ExamRepository examRepo) {
        this.examRepo = examRepo;
    }

    // ── List ──────────────────────────────────────────────────────────

    /**
     * @param groupFilter "all" | "upcoming" | "past" (case-insensitive;
     *                    unknown values default to "all")
     */
    @Transactional(readOnly = true)
    public List<Exam> listForUser(User user, ZoneId zone, String groupFilter) {
        String f = (groupFilter == null) ? "all" : groupFilter.toLowerCase();
        // Reference "now" in the user's local zone for the past/upcoming
        // cut — a Tokyo user viewing "upcoming" should see Thursday exams
        // even when Toronto is still Wednesday.
        LocalDateTime nowLocal = LocalDateTime.now(zone != null ? zone : ZoneId.of("UTC"));

        List<Exam> all = examRepo.findByUserOrderByDateTimeAsc(user);
        switch (f) {
            case "upcoming":
                return all.stream()
                        .filter(e -> e.getDateTime() != null && !e.getDateTime().isBefore(nowLocal))
                        .toList();
            case "past":
                return all.stream()
                        .filter(e -> e.getDateTime() != null && e.getDateTime().isBefore(nowLocal))
                        .sorted(Comparator.comparing(Exam::getDateTime).reversed())
                        .toList();
            default:
                return all;
        }
    }

    /**
     * Nearest N future exams, chronological. Used by the homepage dashboard
     * "Next Exams" card. Callers can pass any ZoneId — we compare against
     * Instant.now() then narrow to `count` because exam dateTime is stored
     * as LocalDateTime in the same reference zone as it was written.
     */
    @Transactional(readOnly = true)
    public List<Exam> listNextN(User user, int count) {
        if (count <= 0) return List.of();
        LocalDateTime pivot = LocalDateTime.now(TrackerService.TORONTO);
        return examRepo.findByUserAndDateTimeAfterOrderByDateTimeAsc(user, pivot).stream()
                .limit(count)
                .toList();
    }

    // ── Single ────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Exam get(User user, Long examId) {
        return examRepo.findById(examId)
                .filter(e -> e.getUser() != null && e.getUser().getId().equals(user.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Exam not found."));
    }

    // ── Create ────────────────────────────────────────────────────────

    @Transactional
    public Exam create(User user, ExamRequest req) {
        LocalDateTime when = resolveDateTime(req);
        if (when == null) {
            throw new IllegalArgumentException("Exam date and time are required.");
        }
        Exam e = new Exam();
        e.setUser(user);
        e.setTitle(safeTrim(req.getTitle()));
        e.setCourse(blankToNull(req.getCourse()));
        e.setDateTime(when);
        e.setMaterial(blankToNull(req.getMaterial()));
        e.setNotes(blankToNull(req.getNotes()));
        e.setLocation(blankToNull(req.getLocation()));
        return examRepo.save(e);
    }

    // ── Update ────────────────────────────────────────────────────────

    @Transactional
    public Exam update(User user, Long examId, ExamRequest req) {
        Exam e = get(user, examId);
        LocalDateTime when = resolveDateTime(req);
        if (when == null) {
            throw new IllegalArgumentException("Exam date and time are required.");
        }
        e.setTitle(safeTrim(req.getTitle()));
        e.setCourse(blankToNull(req.getCourse()));
        e.setDateTime(when);
        e.setMaterial(blankToNull(req.getMaterial()));
        e.setNotes(blankToNull(req.getNotes()));
        e.setLocation(blankToNull(req.getLocation()));
        return examRepo.save(e);
    }

    // ── Delete ────────────────────────────────────────────────────────

    @Transactional
    public void delete(User user, Long examId) {
        Exam e = get(user, examId);
        examRepo.delete(e);
    }

    // ── Helpers (package-private for ExamControllerTest) ──────────────

    /**
     * Resolves the hybrid ExamRequest date/time shape to a single
     * LocalDateTime. Accepts shape A ({@code dateTime} Instant) OR shape B
     * (separate {@code date} / {@code time} / {@code timezone} strings).
     * Returns null when neither shape can produce a valid datetime — the
     * caller raises IllegalArgumentException which the controller maps to
     * 400.
     *
     * Visible for tests so the hybrid logic can be asserted without needing
     * to thread a full User + repository through every case.
     */
    static LocalDateTime resolveDateTime(ExamRequest req) {
        if (req == null) return null;

        // Shape A — client sent a pre-serialized Instant (e.g. AI parser).
        if (req.getDateTime() != null) {
            ZoneId zone = parseZone(req.getTimezone());
            return LocalDateTime.ofInstant(req.getDateTime(), zone);
        }

        // Shape B — separate strings (manual form).
        if (req.getDate() == null || req.getTime() == null) return null;
        try {
            LocalDate d = LocalDate.parse(req.getDate().trim(), DateTimeFormatter.ISO_LOCAL_DATE);
            // Accept "HH:mm" and "HH:mm:ss"
            String t = req.getTime().trim();
            LocalTime lt = (t.length() == 5) ? LocalTime.parse(t, DateTimeFormatter.ofPattern("HH:mm"))
                                             : LocalTime.parse(t);
            return LocalDateTime.of(d, lt);
        } catch (Exception parseFail) {
            return null;
        }
    }

    private static ZoneId parseZone(String tz) {
        if (tz == null || tz.isBlank()) return TrackerService.TORONTO;
        try { return ZoneId.of(tz.trim()); }
        catch (Exception e) { return TrackerService.TORONTO; }
    }

    private static String safeTrim(String s) {
        return s == null ? null : s.trim();
    }

    private static String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
