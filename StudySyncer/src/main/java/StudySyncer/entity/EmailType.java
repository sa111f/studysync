package StudySyncer.entity;

/**
 * Discriminator for every email StudySyncer sends on its accountability
 * schedule. Used as both the send-log key and the unsubscribe scope —
 * a user can disable, say, exam reminders without losing digest emails.
 *
 * New types should stay backwards-compatible: add at the bottom so the
 * EXAM_REMINDER_*D values keep their ordinal positions (they're stored as
 * strings so ordinals don't matter for persistence, but enum rearranging
 * confuses humans reading logs).
 */
public enum EmailType {
    /** Morning "today at a glance" digest. */
    DIGEST,

    /** Evening nudge when a task went overdue yesterday. */
    OVERDUE_REMINDER,

    /** Exam is exactly 7 days away in the user's local calendar. */
    EXAM_REMINDER_7D,

    /** Exam is exactly 3 days away. */
    EXAM_REMINDER_3D,

    /** Exam is exactly 1 day away (fires the day before). */
    EXAM_REMINDER_1D,

    /** Existing daily-goal-reached email (retrofitted to the send log). */
    GOAL_REACHED,

    /** Existing daily-goal-missed email (retrofitted to the send log). */
    GOAL_MISSED
}
