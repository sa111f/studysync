package StudySyncer.entity;

/**
 * Kind of work a task represents. Stored as a string via
 * @Enumerated(EnumType.STRING) on Task.type so the column can be read as
 * human-readable values.
 */
public enum TaskType {
    ASSIGNMENT,
    LAB,
    HOMEWORK,
    PROJECT,
    READING,
    OTHER
}
