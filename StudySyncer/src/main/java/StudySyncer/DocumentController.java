package StudySyncer;

import StudySyncer.dto.StudyEstimationRequest;
import StudySyncer.dto.StudyEstimationResponse;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * STUD-54: Accept uploaded PDF file.
 * STUD-55: Extract plain text from the PDF using Apache PDFBox.
 * STUD-56: Validate file type (PDF only) and size (max 10 MB).
 * STUD-58/62/63: Estimate study time from notes/PDF text.
 */
@RestController
@RequestMapping("/api/document")
public class DocumentController {

    /** STUD-56: 10 MB — reasonable limit for text-based lecture PDFs */
    private static final long MAX_PDF_BYTES = 10L * 1024 * 1024;

    private final StudyEstimationService estimationService;

    public DocumentController(StudyEstimationService estimationService) {
        this.estimationService = estimationService;
    }

    /** POST /api/document/extract — STUD-54/55/56 */
    @PostMapping("/extract")
    public ResponseEntity<?> extractPdf(@RequestParam("file") MultipartFile file) {

        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "No file was uploaded."));
        }

        String contentType = file.getContentType() != null ? file.getContentType() : "";
        String filename    = file.getOriginalFilename() != null
                             ? file.getOriginalFilename().toLowerCase() : "";
        if (!contentType.equals("application/pdf") && !filename.endsWith(".pdf")) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Only PDF files are accepted."));
        }

        if (file.getSize() > MAX_PDF_BYTES) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "File is too large. Maximum allowed size is 10 MB."));
        }

        try (PDDocument doc = PDDocument.load(file.getInputStream())) {
            String text      = new PDFTextStripper().getText(doc).trim();
            int    pageCount = doc.getNumberOfPages();
            if (text.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error",
                                "No text could be extracted. The PDF may contain only scanned images."));
            }
            return ResponseEntity.ok(Map.of("text", text, "pageCount", pageCount));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Could not read the PDF. Please try a different file."));
        }
    }

    /** POST /api/document/estimate — STUD-58/62/63 */
    @PostMapping("/estimate")
    public ResponseEntity<?> estimate(@RequestBody StudyEstimationRequest request) {
        if (request == null || request.getText() == null || request.getText().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "No text provided for estimation."));
        }
        try {
            String difficulty = request.getDifficulty() != null ? request.getDifficulty() : "medium";
            StudyEstimationResponse result = estimationService.estimate(request.getText(), difficulty);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Estimation failed. Please try again."));
        }
    }
}
