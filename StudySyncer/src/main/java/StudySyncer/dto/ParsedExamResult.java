package StudySyncer.dto;

import java.time.Instant;

/**
 * Outcome of an AI exam-parse attempt. Mirrors ParsedTaskResult shape so
 * AiController can map failure reasons to HTTP statuses with the same code
 * path for both /parse-task and /parse-exam.
 */
public final class ParsedExamResult {

    public enum FailureReason {
        INVALID_INPUT,
        AI_UNAVAILABLE,
        AI_AMBIGUOUS,
        TIMEOUT
    }

    private final boolean       success;
    private final String        title;
    private final Instant       dateTime;
    private final String        resolvedDateHuman;
    private final String        course;
    private final String        material;
    private final String        location;
    private final FailureReason failureReason;
    private final String        hint;

    private ParsedExamResult(boolean success,
                             String title, Instant dateTime, String resolvedDateHuman,
                             String course, String material, String location,
                             FailureReason failureReason, String hint) {
        this.success           = success;
        this.title             = title;
        this.dateTime          = dateTime;
        this.resolvedDateHuman = resolvedDateHuman;
        this.course            = course;
        this.material          = material;
        this.location          = location;
        this.failureReason     = failureReason;
        this.hint              = hint;
    }

    public static ParsedExamResult success(String title, Instant dateTime,
                                           String resolvedDateHuman, String course,
                                           String material, String location) {
        return new ParsedExamResult(true,
                title, dateTime, resolvedDateHuman, course, material, location,
                null, null);
    }

    public static ParsedExamResult failure(FailureReason reason, String hint) {
        return new ParsedExamResult(false,
                null, null, null, null, null, null,
                reason, hint);
    }

    public boolean       isSuccess()            { return success; }
    public String        getTitle()             { return title; }
    public Instant       getDateTime()          { return dateTime; }
    public String        getResolvedDateHuman() { return resolvedDateHuman; }
    public String        getCourse()            { return course; }
    public String        getMaterial()          { return material; }
    public String        getLocation()          { return location; }
    public FailureReason getFailureReason()     { return failureReason; }
    public String        getHint()              { return hint; }
}
