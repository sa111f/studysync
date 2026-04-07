package StudySyncer;

import StudySyncer.dto.DeadlineRequest;
import StudySyncer.dto.DeadlineResponse;
import StudySyncer.entity.Deadline;
import StudySyncer.entity.User;
import StudySyncer.repository.DeadlineRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

@Service
public class DeadlineService {

    private final DeadlineRepository repo;

    public DeadlineService(DeadlineRepository repo) {
        this.repo = repo;
    }

    // ── CRUD ──────────────────────────────────────────────

    public List<DeadlineResponse> getAll(User user) {
        return repo.findByUserOrderByDueDateAsc(user)
                   .stream()
                   .map(this::toResponse)
                   .toList();
    }

    public DeadlineResponse create(User user, DeadlineRequest req) {
        validate(req);
        Deadline d = new Deadline();
        d.setUser(user);
        applyFields(d, req);
        return toResponse(repo.save(d));
    }

    public Optional<DeadlineResponse> update(User user, Long id, DeadlineRequest req) {
        validate(req);
        return repo.findById(id)
                   .filter(d -> d.getUser().getId().equals(user.getId()))
                   .map(d -> {
                       applyFields(d, req);
                       return toResponse(repo.save(d));
                   });
    }

    public boolean delete(User user, Long id) {
        return repo.findById(id)
                   .filter(d -> d.getUser().getId().equals(user.getId()))
                   .map(d -> { repo.delete(d); return true; })
                   .orElse(false);
    }

    // ── Helpers ───────────────────────────────────────────

    private void validate(DeadlineRequest req) {
        if (req.getTitle() == null || req.getTitle().isBlank())
            throw new IllegalArgumentException("Title is required.");
        if (req.getDueDate() == null || req.getDueDate().isBlank())
            throw new IllegalArgumentException("Due date is required.");
    }

    private void applyFields(Deadline d, DeadlineRequest req) {
        d.setTitle(req.getTitle().trim());
        d.setSubject(req.getSubject() != null ? req.getSubject().trim() : null);
        d.setType(req.getType() != null ? req.getType().trim() : "other");
        d.setDueDate(LocalDate.parse(req.getDueDate()));
        d.setDueTime(req.getDueTime() != null && !req.getDueTime().isBlank()
                ? LocalTime.parse(req.getDueTime()) : null);
        d.setNotes(req.getNotes() != null ? req.getNotes().trim() : null);
    }

    // ── Urgency / Priority computation ────────────────────

    /**
     * Urgency tiers (ascending urgency):
     *   later    → due in 8+ days
     *   upcoming → due in 4–7 days
     *   soon     → due in 2–3 days
     *   tomorrow → due in exactly 1 day
     *   today    → due today
     *   overdue  → past due date
     *
     * Priority score (higher = study this first):
     *   overdue  → 10 000 + |days| * 200
     *   today    → 9 000
     *   tomorrow → 8 000
     *   soon (2) → 7 800
     *   soon (3) → 7 600
     *   upcoming → 5 000 – days * 100
     *   later    → 1 000 – min(days, 999)
     */
    DeadlineResponse toResponse(Deadline d) {
        DeadlineResponse r = new DeadlineResponse();
        r.setId(d.getId());
        r.setTitle(d.getTitle());
        r.setSubject(d.getSubject());
        r.setType(d.getType());
        r.setDueDate(d.getDueDate().toString());
        r.setDueTime(d.getDueTime() != null ? d.getDueTime().format(DateTimeFormatter.ofPattern("HH:mm")) : null);
        r.setNotes(d.getNotes());
        r.setCreatedAt(d.getCreatedAt() != null ? d.getCreatedAt().toString() : null);

        long days = ChronoUnit.DAYS.between(LocalDate.now(), d.getDueDate());
        r.setDaysRemaining((int) days);

        String urgency;
        int    score;

        if (days < 0) {
            urgency = "overdue";
            score   = 10_000 + (int) Math.abs(days) * 200;
        } else if (days == 0) {
            urgency = "today";
            score   = 9_000;
        } else if (days == 1) {
            urgency = "tomorrow";
            score   = 8_000;
        } else if (days <= 3) {
            urgency = "soon";
            score   = 7_800 - (int)(days - 2) * 200;
        } else if (days <= 7) {
            urgency = "upcoming";
            score   = 5_000 - (int)(days - 4) * 100;
        } else {
            urgency = "later";
            score   = Math.max(1, 1_000 - (int) Math.min(days, 999L));
        }

        r.setUrgency(urgency);
        r.setPriorityScore(score);
        return r;
    }
}
