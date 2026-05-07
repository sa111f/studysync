package StudySyncer.entity;

/**
 * Lifecycle state of a Task. Stored as a string via @Enumerated(EnumType.STRING).
 * Transition TO COMPLETED stamps Task.completedAt; any transition away from
 * COMPLETED clears it (handled by the service layer, not the entity).
 */
public enum TaskStatus {
    NOT_STARTED,
    IN_PROGRESS,
    COMPLETED
}
