package StudySyncer;

import StudySyncer.dto.ParsedSyllabusResult;
import StudySyncer.entity.User;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * POST /api/syllabus/upload — multipart PDF → extracted text → AI parse →
 * list of tasks/exams for the frontend's review step.
 *
 * Status code matrix:
 *   200 OK                — body: {courseCode, items[], truncated}
 *   400 Bad Request       — bad file (too big / not a pdf / encrypted / empty text)
 *   401 Unauthorized      — no session
 *   429 Too Many Requests — per-user syllabus bucket exhausted
 *   502 Bad Gateway       — AI returned unparseable output
 *   503 Service Unavailable — AI not configured / HTTP failure
 *   504 Gateway Timeout   — AI call timed out
 *
 * Does NOT persist anything. The frontend reviews the items, the user
 * toggles / edits, then POST /api/bulk/import writes them in one shot.
 */
@RestController
@RequestMapping("/api/syllabus")
public class SyllabusController {

    private static final Logger log = LoggerFactory.getLogger(SyllabusController.class);

    private final PdfExtractionService      pdfService;
    private final AiSyllabusParserService   aiService;
    private final SyllabusRateLimiter       rateLimiter;
    private final UserService               userService;

    public SyllabusController(PdfExtractionService pdfService,
                              AiSyllabusParserService aiService,
                              SyllabusRateLimiter rateLimiter,
                              UserService userService) {
        this.pdfService  = pdfService;
        this.aiService   = aiService;
        this.rateLimiter = rateLimiter;
        this.userService = userService;
    }

    @PostMapping(value = "/upload", consumes = "multipart/form-data")
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file,
                                    @RequestParam(value = "timezone", required = false) String tz,
                                    HttpSession session) {
        User user = resolveUser(session);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Not logged in."));
        }

        // ── Validate the upload before burning the rate-limit slot.
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Please select a PDF file."));
        }
        if (file.getSize() > PdfExtractionService.MAX_PDF_BYTES) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "File is too large. Maximum 10 MB."));
        }
        // Content-type check — browsers populate this from the file extension + magic bytes.
        // Also accept any "application/*" type as long as the filename ends in .pdf,
        // because some environments report "application/octet-stream" for PDFs.
        String ct       = file.getContentType() != null ? file.getContentType() : "";
        String filename = file.getOriginalFilename() != null
                            ? file.getOriginalFilename().toLowerCase()
                            : "";
        boolean looksLikePdf = ct.equals("application/pdf") || filename.endsWith(".pdf");
        if (!looksLikePdf) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Only PDF files are accepted."));
        }

        // ── Rate-limit gate (BEFORE burning tokens).
        SyllabusRateLimiter.Decision gate = rateLimiter.tryAcquire(user.getId());
        if (!gate.isAllowed()) {
            log.info("[SYLLABUS] Rate-limited userId={} — retry={}s",
                    user.getId(), gate.getRetryAfterSeconds());
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(gate.getRetryAfterSeconds()));
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .headers(headers)
                    .body(Map.of(
                            "error", "Too many syllabus uploads — try again in an hour.",
                            "retryAfterSeconds", gate.getRetryAfterSeconds()));
        }

        // ── Extract PDF text.
        String text;
        try {
            text = pdfService.extractText(file.getBytes());
        } catch (PdfExtractionException pe) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", pe.getMessage()));
        } catch (IOException ioe) {
            log.warn("[SYLLABUS] Could not read upload bytes: {}", ioe.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Could not read the uploaded file."));
        }

        // ── Resolve timezone (same pattern as TaskController / ExamController).
        ZoneId zone;
        try {
            zone = (tz != null && !tz.isBlank()) ? ZoneId.of(tz) : ZoneId.of("UTC");
        } catch (Exception e) {
            zone = ZoneId.of("UTC");
        }

        // ── AI extraction.
        ParsedSyllabusResult parsed = aiService.parseSyllabus(text, zone);

        if (parsed.isSuccess()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("courseCode", parsed.getCourseCode());
            body.put("items",      parsed.getItems());
            body.put("truncated",  parsed.isTruncated());
            return ResponseEntity.ok(body);
        }

        switch (parsed.getFailureReason()) {
            case INVALID_INPUT:
                return ResponseEntity.badRequest()
                        .body(Map.of("error", hintOr(parsed, "Syllabus text was empty.")));
            case AI_UNAVAILABLE:
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(Map.of("error", hintOr(parsed, "AI is currently unavailable.")));
            case TIMEOUT:
                return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                        .body(Map.of("error", hintOr(parsed, "AI took too long to respond.")));
            case AI_AMBIGUOUS:
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                        .body(Map.of("error", hintOr(parsed, "AI returned unparseable output.")));
            default:
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(Map.of("error", "Unexpected failure."));
        }
    }

    private static String hintOr(ParsedSyllabusResult r, String fallback) {
        return r.getHint() != null && !r.getHint().isBlank() ? r.getHint() : fallback;
    }

    private User resolveUser(HttpSession session) {
        Long id = AuthController.resolveUserId(session);
        if (id == null) return null;
        return userService.findById(id).orElse(null);
    }
}
