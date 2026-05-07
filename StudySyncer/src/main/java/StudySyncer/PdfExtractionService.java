package StudySyncer;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Thin wrapper around Apache PDFBox 3.x for text extraction.
 *
 * Guardrails (all raise {@link PdfExtractionException} with user-safe messages):
 *   - null / empty input                → "The file is empty."
 *   - byte size > MAX_PDF_BYTES (10 MB) → "File is too large. Maximum 10 MB."
 *   - encrypted PDFs                    → "This PDF is password-protected. Remove the password and try again."
 *   - extracted text empty              → "No text could be extracted. The PDF may contain only scanned images."
 *   - extracted text > MAX_TEXT_CHARS   → "PDF text is too long to process."
 *   - any IOException during parse      → "Could not read this PDF. Please try a different file."
 *
 * PDFBox 3.x API note: 2.x's {@code PDDocument.load(byte[])} was removed.
 * Use {@link Loader#loadPDF(byte[])} and close the resulting PDDocument in
 * a try-with-resources block.
 */
@Service
public class PdfExtractionService {

    private static final Logger log = LoggerFactory.getLogger(PdfExtractionService.class);

    /** Hard file-size cap. Also enforced by Spring multipart config. */
    static final long MAX_PDF_BYTES = 10L * 1024 * 1024;

    /** Drop anything above this — very likely a scanned book or OCR artifact. */
    static final int  MAX_TEXT_CHARS = 100_000;

    /**
     * Extract plain text from a PDF's bytes. Never returns null or empty —
     * raises {@link PdfExtractionException} on any failure mode above.
     */
    public String extractText(byte[] pdfBytes) {
        if (pdfBytes == null || pdfBytes.length == 0) {
            throw new PdfExtractionException("The file is empty.");
        }
        if (pdfBytes.length > MAX_PDF_BYTES) {
            throw new PdfExtractionException("File is too large. Maximum 10 MB.");
        }

        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            // Encrypted PDFs with the empty password may open successfully; skip those
            // anyway because PDFTextStripper often returns garbled content from them
            // and the user should re-export without a password.
            if (doc.isEncrypted()) {
                throw new PdfExtractionException(
                        "This PDF is password-protected. Remove the password and try again.");
            }

            PDFTextStripper stripper = new PDFTextStripper();
            // Sort by position so multi-column syllabi don't interleave lines
            // from different columns — makes the extracted text read top-to-bottom.
            stripper.setSortByPosition(true);

            String text = stripper.getText(doc);
            if (text == null) text = "";
            text = text.trim();

            if (text.isEmpty()) {
                throw new PdfExtractionException(
                        "No text could be extracted. The PDF may contain only scanned images.");
            }
            if (text.length() > MAX_TEXT_CHARS) {
                // Log the size so we can tune MAX_TEXT_CHARS if real syllabi hit it.
                log.warn("[PDF] Rejected oversized extraction: chars={}", text.length());
                throw new PdfExtractionException("PDF text is too long to process.");
            }
            return text;

        } catch (InvalidPasswordException e) {
            throw new PdfExtractionException(
                    "This PDF is password-protected. Remove the password and try again.", e);
        } catch (PdfExtractionException e) {
            throw e;   // don't re-wrap our own
        } catch (Exception e) {
            log.warn("[PDF] Extraction failed: {}", e.getMessage());
            throw new PdfExtractionException(
                    "Could not read this PDF. Please try a different file.", e);
        }
    }
}
