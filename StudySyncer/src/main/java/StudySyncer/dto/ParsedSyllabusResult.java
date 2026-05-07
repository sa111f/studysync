package StudySyncer.dto;

import java.util.Collections;
import java.util.List;

/**
 * Result of an AI syllabus extraction. Parallel to ParsedTaskResult /
 * ParsedExamResult but carries a BATCH of items.
 */
public final class ParsedSyllabusResult {

    public enum FailureReason {
        INVALID_INPUT,      // empty / unusable text
        AI_UNAVAILABLE,     // missing key / HTTP failure / non-2xx
        AI_AMBIGUOUS,       // tool block missing / malformed
        TIMEOUT
    }

    private final boolean       success;
    private final String        courseCode;
    private final List<SyllabusItem> items;
    private final boolean       truncated;
    private final FailureReason failureReason;
    private final String        hint;

    private ParsedSyllabusResult(boolean success, String courseCode,
                                 List<SyllabusItem> items, boolean truncated,
                                 FailureReason failureReason, String hint) {
        this.success       = success;
        this.courseCode    = courseCode;
        this.items         = items != null ? items : Collections.emptyList();
        this.truncated     = truncated;
        this.failureReason = failureReason;
        this.hint          = hint;
    }

    public static ParsedSyllabusResult success(String courseCode,
                                               List<SyllabusItem> items,
                                               boolean truncated) {
        return new ParsedSyllabusResult(true, courseCode, items, truncated, null, null);
    }

    public static ParsedSyllabusResult failure(FailureReason reason, String hint) {
        return new ParsedSyllabusResult(false, null, Collections.emptyList(), false, reason, hint);
    }

    public boolean           isSuccess()         { return success; }
    public String            getCourseCode()     { return courseCode; }
    public List<SyllabusItem> getItems()         { return items; }
    public boolean           isTruncated()       { return truncated; }
    public FailureReason     getFailureReason()  { return failureReason; }
    public String            getHint()           { return hint; }
}
