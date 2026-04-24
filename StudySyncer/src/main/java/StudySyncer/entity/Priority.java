package StudySyncer.entity;

/**
 * Shared priority tier used by tasks (and future entities that carry a priority).
 * Persisted as a string via @Enumerated(EnumType.STRING) so enum reordering or
 * renaming in code does not shuffle values already in the database.
 */
public enum Priority {
    LOW,
    MEDIUM,
    HIGH
}
