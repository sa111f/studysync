package StudySyncer;

/**
 * Thrown by {@link PdfExtractionService} when a PDF can't be turned into
 * usable text. Controllers catch this and return 400 with the message —
 * the message is safe to show to end users (no stack traces, no paths).
 */
public class PdfExtractionException extends RuntimeException {
    public PdfExtractionException(String message) {
        super(message);
    }
    public PdfExtractionException(String message, Throwable cause) {
        super(message, cause);
    }
}
