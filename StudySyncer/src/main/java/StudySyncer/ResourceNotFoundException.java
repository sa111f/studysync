package StudySyncer;

/**
 * Thrown by services when a requested resource either does not exist OR exists
 * but is owned by a different user.  Controllers catch this and return 404 —
 * never distinguishing the two cases, to avoid leaking whether an id is valid
 * via probing.
 */
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
