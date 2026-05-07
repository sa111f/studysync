package StudySyncer.dto;

import StudySyncer.entity.Priority;
import StudySyncer.entity.TaskType;

import java.time.LocalDate;

/**
 * Result of an AI task-parse attempt. Immutable. Use the static factories
 * (success / failure) rather than calling constructors directly.
 *
 * Rationale for one concrete class vs. a sealed hierarchy: keeps Jackson
 * serialization trivial, matches the project's existing plain-POJO DTO
 * pattern, and lets the controller branch on isSuccess() without casts.
 */
public final class ParsedTaskResult {

    public enum FailureReason {
        /** Empty, whitespace-only, or > 500 chars. */
        INVALID_INPUT,
        /** Missing API key, non-2xx response, network error, etc. */
        AI_UNAVAILABLE,
        /** Tool returned fields that failed our schema/enum validation. */
        AI_AMBIGUOUS,
        /** RestTemplate / underlying HttpClient timed out. */
        TIMEOUT
    }

    private final boolean       success;
    private final String        title;
    private final LocalDate     dueDate;
    private final String        resolvedDateHuman;
    private final String        course;
    private final TaskType      type;
    private final Priority      priority;
    private final FailureReason failureReason;
    private final String        hint;

    private ParsedTaskResult(boolean success,
                             String title, LocalDate dueDate, String resolvedDateHuman,
                             String course, TaskType type, Priority priority,
                             FailureReason failureReason, String hint) {
        this.success           = success;
        this.title             = title;
        this.dueDate           = dueDate;
        this.resolvedDateHuman = resolvedDateHuman;
        this.course            = course;
        this.type              = type;
        this.priority          = priority;
        this.failureReason     = failureReason;
        this.hint              = hint;
    }

    // ── Factories ─────────────────────────────────────────────────────

    public static ParsedTaskResult success(String title, LocalDate dueDate,
                                           String resolvedDateHuman, String course,
                                           TaskType type, Priority priority) {
        return new ParsedTaskResult(true,
                title, dueDate, resolvedDateHuman, course, type, priority,
                null, null);
    }

    public static ParsedTaskResult failure(FailureReason reason, String hint) {
        return new ParsedTaskResult(false,
                null, null, null, null, null, null,
                reason, hint);
    }

    // ── Getters ───────────────────────────────────────────────────────

    public boolean       isSuccess()          { return success; }
    public String        getTitle()           { return title; }
    public LocalDate     getDueDate()         { return dueDate; }
    public String        getResolvedDateHuman() { return resolvedDateHuman; }
    public String        getCourse()          { return course; }
    public TaskType      getType()            { return type; }
    public Priority      getPriority()        { return priority; }
    public FailureReason getFailureReason()   { return failureReason; }
    public String        getHint()            { return hint; }
}
