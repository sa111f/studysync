package StudySyncer;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for PdfExtractionService. Uses PDFBox itself to generate
 * real PDFs in-memory so the extractor round-trips actual bytes — no
 * binary fixtures checked into the repo.
 */
class PdfExtractionServiceTest {

    private final PdfExtractionService service = new PdfExtractionService();

    // ── Null / empty byte array ────────────────────────────────────

    @Test
    void extractText_nullBytes_throws() {
        assertThatThrownBy(() -> service.extractText(null))
                .isInstanceOf(PdfExtractionException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void extractText_emptyBytes_throws() {
        assertThatThrownBy(() -> service.extractText(new byte[0]))
                .isInstanceOf(PdfExtractionException.class)
                .hasMessageContaining("empty");
    }

    // ── Oversized PDF (> 10 MB) ────────────────────────────────────

    @Test
    void extractText_over10MB_rejects() {
        byte[] big = new byte[(int) PdfExtractionService.MAX_PDF_BYTES + 1];
        // Content doesn't matter — size check is byte-length based.
        assertThatThrownBy(() -> service.extractText(big))
                .isInstanceOf(PdfExtractionException.class)
                .hasMessageContaining("too large");
    }

    // ── Garbage bytes (not a PDF at all) ───────────────────────────

    @Test
    void extractText_nonPdfBytes_throws() {
        byte[] junk = "this is not a pdf, clearly".getBytes();
        assertThatThrownBy(() -> service.extractText(junk))
                .isInstanceOf(PdfExtractionException.class)
                .hasMessageContaining("Could not read");
    }

    // ── Valid PDF with text → round-trip ───────────────────────────

    @Test
    void extractText_validPdfWithText_returnsText() throws Exception {
        byte[] pdf = buildPdfWithText("Syllabus week 1 — Introduction. HW1 due Sep 12.");
        String out = service.extractText(pdf);
        assertThat(out).contains("Syllabus").contains("HW1");
    }

    // ── Encrypted PDF → clear message ──────────────────────────────

    @Test
    void extractText_encryptedPdf_throwsWithClearMessage() throws Exception {
        byte[] encrypted = buildEncryptedPdf("secret");
        assertThatThrownBy(() -> service.extractText(encrypted))
                .isInstanceOf(PdfExtractionException.class)
                .hasMessageContaining("password-protected");
    }

    // ── PDF with no extractable text (empty page) ──────────────────

    @Test
    void extractText_emptyPdf_throws() throws Exception {
        // Build a PDF with a blank page — PDFTextStripper returns "" on these,
        // which the service should surface as a helpful error rather than a
        // successful-but-empty result.
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);

            assertThatThrownBy(() -> service.extractText(baos.toByteArray()))
                    .isInstanceOf(PdfExtractionException.class)
                    .hasMessageContaining("No text could be extracted");
        }
    }

    // ── Helpers ────────────────────────────────────────────────────

    private byte[] buildPdfWithText(String text) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText(text);
                cs.endText();
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    private byte[] buildEncryptedPdf(String password) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            StandardProtectionPolicy policy = new StandardProtectionPolicy(
                    password, password, new AccessPermission());
            policy.setEncryptionKeyLength(128);
            doc.protect(policy);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }
}
