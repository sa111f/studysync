package StudySyncer;

import StudySyncer.dto.TaskRequest;
import StudySyncer.dto.TaskResponse;
import StudySyncer.entity.Priority;
import StudySyncer.entity.Task;
import StudySyncer.entity.TaskStatus;
import StudySyncer.entity.TaskType;
import StudySyncer.entity.User;
import StudySyncer.repository.StudySessionRepository;
import StudySyncer.repository.TaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Task CRUD service. All reads and writes are scoped to a user — callers
 * pass the authenticated User (resolved in the controller) and this class
 * never leaks another user's rows.
 *
 * "Not found OR not yours" is a single ResourceNotFoundException so the
 * controller always returns 404 for both cases, preventing id-probing.
 */
@Service
public class TaskService {

    private final TaskRepository         taskRepo;
    private final StudySessionRepository sessionRepo;

    public TaskService(TaskRepository taskRepo, StudySessionRepository sessionRepo) {
        this.taskRepo    = taskRepo;
        this.sessionRepo = sessionRepo;
    }

    // ── Read ──────────────────────────────────────────────────────────

    /**
     * Lists tasks for a user, optionally constrained by the supplied filter.
     *
     * @param user           authenticated user
     * @param filter         one of "active", "completed", "overdue", "all"
     *                       (null or unknown → "all")
     * @param userLocalToday the user-local date used for the overdue filter;
     *                       must not be null when filter == "overdue"
     * @return tasks ordered by dueDate ASC, then createdAt ASC (tie-breaker)
     */
    @Transactional(readOnly = true)
    public List<Task> listForUser(User user, String filter, LocalDate userLocalToday) {
        List<Task> raw;
        String f = filter == null ? "all" : filter.toLowerCase();

        switch (f) {
            case "completed" ->
                raw = taskRepo.findByUserAndStatus(user, TaskStatus.COMPLETED);
            case "active" -> {
                // "active" = NOT_STARTED or IN_PROGRESS. Two lookups + merge
                // keeps the repository surface small; no IN query needed.
                List<Task> ns = taskRepo.findByUserAndStatus(user, TaskStatus.NOT_STARTED);
                List<Task> ip = taskRepo.findByUserAndStatus(user, TaskStatus.IN_PROGRESS);
                raw = new java.util.ArrayList<>(ns.size() + ip.size());
                raw.addAll(ns);
                raw.addAll(ip);
            }
            case "overdue" -> {
                // Active only, due before today. Combined via the existing
                // composite finder — two calls (one per status) merged.
                List<Task> ns = taskRepo.findByUserAndDueDateBeforeAndStatusNot(
                        user, userLocalToday, TaskStatus.COMPLETED);
                // findByUserAndDueDateBeforeAndStatusNot already excludes COMPLETED,
                // so the single call covers both NOT_STARTED and IN_PROGRESS.
                raw = ns;
            }
            default ->
                raw = taskRepo.findByUserOrderByDueDateAsc(user);
        }

        // Stable ordering across all filters: dueDate ASC, then createdAt ASC.
        raw.sort(Comparator
                .comparing(Task::getDueDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Task::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())));
        return raw;
    }

    /**
     * Loads a single task by id, verifying ownership.
     * Throws ResourceNotFoundException for both "missing" and "not yours".
     */
    @Transactional(readOnly = true)
    public Task get(User user, Long taskId) {
        return taskRepo.findById(taskId)
                .filter(t -> t.getUser() != null && t.getUser().getId().equals(user.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Task not found."));
    }

    // ── Write ─────────────────────────────────────────────────────────

    /**
     * Creates a task for the given user. Missing enum fields receive defaults
     * (OTHER / MEDIUM / NOT_STARTED) — that asymmetry is documented on
     * TaskRequest and lets the same DTO serve POST and PUT.
     */
    @Transactional
    public Task create(User user, TaskRequest req) {
        Task t = new Task();
        t.setUser(user);
        t.setTitle(req.getTitle() == null ? null : req.getTitle().trim());
        t.setNotes(req.getNotes());
        t.setDueDate(req.getDueDate());
        t.setCourse(blankToNull(req.getCourse()));

        t.setType(     req.getType()     != null ? req.getType()     : TaskType.OTHER);
        t.setPriority( req.getPriority() != null ? req.getPriority() : Priority.MEDIUM);
        t.setStatus(   req.getStatus()   != null ? req.getStatus()   : TaskStatus.NOT_STARTED);

        // If the client creates a task already flagged COMPLETED, stamp completedAt now
        // so analytics match what @PrePersist on update would do.
        if (t.getStatus() == TaskStatus.COMPLETED) {
            t.setCompletedAt(LocalDateTime.now());
        }

        return taskRepo.save(t);
    }

    /**
     * Full replace of a task's editable fields.
     *
     * Enforces the "type / priority / status required on update" rule from the
     * TaskRequest contract: missing any of these raises IllegalArgumentException,
     * which the controller maps to 400.
     *
     * completedAt transitions:
     *   - to COMPLETED   → set completedAt = now (if not already COMPLETED)
     *   - away from it   → clear completedAt
     */
    @Transactional
    public Task update(User user, Long taskId, TaskRequest req) {
        Task t = get(user, taskId);

        if (req.getType()     == null) throw new IllegalArgumentException("Type is required.");
        if (req.getPriority() == null) throw new IllegalArgumentException("Priority is required.");
        if (req.getStatus()   == null) throw new IllegalArgumentException("Status is required.");

        TaskStatus oldStatus = t.getStatus();
        TaskStatus newStatus = req.getStatus();

        t.setTitle(req.getTitle() == null ? null : req.getTitle().trim());
        t.setNotes(req.getNotes());
        t.setDueDate(req.getDueDate());
        t.setCourse(blankToNull(req.getCourse()));
        t.setType(req.getType());
        t.setPriority(req.getPriority());
        t.setStatus(newStatus);

        applyCompletedAtTransition(t, oldStatus, newStatus);

        return taskRepo.save(t);
    }

    /** Narrow patch that only updates status + the paired completedAt field. */
    @Transactional
    public Task updateStatus(User user, Long taskId, TaskStatus newStatus) {
        Task t = get(user, taskId);

        TaskStatus oldStatus = t.getStatus();
        t.setStatus(newStatus);

        applyCompletedAtTransition(t, oldStatus, newStatus);

        return taskRepo.save(t);
    }

    @Transactional
    public void delete(User user, Long taskId) {
        Task t = get(user, taskId);
        taskRepo.delete(t);
        // Soft reference: any StudySession rows with this taskId keep it.
        // Phase 3 renders those sessions with no task badge.
    }

    // ── Response mapping with logged-time aggregation ─────────────────

    /**
     * Maps a list of tasks to TaskResponses, batching the per-task seconds-logged
     * aggregation into a single GROUP BY query (avoids N+1).
     */
    @Transactional(readOnly = true)
    public List<TaskResponse> toResponses(User user, List<Task> tasksList, LocalDate today) {
        if (tasksList == null || tasksList.isEmpty()) return Collections.emptyList();

        Map<Long, Long> minutesByTask = sumMinutesForTasks(user, tasksList);

        List<TaskResponse> out = new java.util.ArrayList<>(tasksList.size());
        for (Task t : tasksList) {
            long mins = minutesByTask.getOrDefault(t.getId(), 0L);
            out.add(TaskResponse.from(t, today, mins * 60L));
        }
        return out;
    }

    /** Maps one task to a response with its own seconds-logged total. */
    @Transactional(readOnly = true)
    public TaskResponse toResponse(User user, Task task, LocalDate today) {
        long mins = sessionRepo.sumDurationByUserAndTaskId(user, task.getId());
        return TaskResponse.from(task, today, mins * 60L);
    }

    /**
     * Executes the batch aggregation and converts the raw Object[] rows into
     * a taskId → total-minutes map. Extracted so both the list response mapper
     * and any future batch callers share one query.
     */
    private Map<Long, Long> sumMinutesForTasks(User user, List<Task> tasksList) {
        List<Long> ids = tasksList.stream().map(Task::getId).toList();
        List<Object[]> rows = sessionRepo.sumDurationGroupedByTaskIds(user, ids);
        Map<Long, Long> out = new HashMap<>(rows.size() * 2);
        for (Object[] row : rows) {
            Long taskId = (Long) row[0];
            long minutes = ((Number) row[1]).longValue();
            out.put(taskId, minutes);
        }
        return out;
    }

    // ── Internals ─────────────────────────────────────────────────────

    /**
     * Stamps or clears completedAt based on a status transition.
     * Only re-stamps on a FRESH transition into COMPLETED so a PUT that
     * leaves status = COMPLETED doesn't overwrite the original completion time.
     */
    private void applyCompletedAtTransition(Task t, TaskStatus oldStatus, TaskStatus newStatus) {
        boolean becomingCompleted = newStatus == TaskStatus.COMPLETED && oldStatus != TaskStatus.COMPLETED;
        boolean leavingCompleted  = newStatus != TaskStatus.COMPLETED && oldStatus == TaskStatus.COMPLETED;

        if (becomingCompleted) {
            t.setCompletedAt(LocalDateTime.now());
        } else if (leavingCompleted) {
            t.setCompletedAt(null);
        }
    }

    private static String blankToNull(String s) {
        if (s == null) return null;
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
