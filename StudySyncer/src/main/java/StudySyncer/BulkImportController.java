package StudySyncer;

import StudySyncer.dto.ExamRequest;
import StudySyncer.dto.TaskRequest;
import StudySyncer.entity.User;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * POST /api/bulk/import — all-or-nothing create of a mixed batch of tasks
 * and exams. Used by the syllabus-import flow after the user has reviewed
 * and edited the AI-extracted items.
 *
 * Request shape:
 *   {"items": [
 *       {"kind": "task", "title": "Homework 1", "dueDate": "2026-05-12",
 *        "taskType": "HOMEWORK", "course": "EECS 281"},
 *       {"kind": "exam", "title": "Midterm 1",
 *        "dateTime": "2026-06-15T09:00:00-04:00", "course": "EECS 281",
 *        "material": "Chapters 1-5"},
 *       ...
 *   ]}
 *
 * Response shape (200):
 *   {"tasksCreated": N, "examsCreated": M, "errors": []}
 *
 * Response shape (400) — any one item fails validation:
 *   {"tasksCreated": 0, "examsCreated": 0, "errors": [
 *       {"index": 3, "error": "Title is required."}, ...
 *   ]}
 *
 * Rollback semantics: the @Transactional boundary covers the whole batch.
 * We pre-validate every item BEFORE any create call, so a validation
 * failure is a clean 400 with no DB writes. If a downstream create throws
 * after validation passes (e.g. DB constraint violation), the transaction
 * aborts and the client sees a 500 — we don't partially save.
 *
 * Parsing note: Jackson can't polymorphically deserialize mixed shapes
 * into a single DTO, so we accept the raw JsonNode and split by kind
 * after reading. Small cost; avoids introducing @JsonTypeInfo annotations.
 */
@RestController
@RequestMapping("/api/bulk")
public class BulkImportController {

    private static final Logger log = LoggerFactory.getLogger(BulkImportController.class);

    private final TaskService taskService;
    private final ExamService examService;
    private final UserService userService;
    private final ObjectMapper mapper = new ObjectMapper();

    public BulkImportController(TaskService taskService,
                                ExamService examService,
                                UserService userService) {
        this.taskService = taskService;
        this.examService = examService;
        this.userService = userService;
    }

    @PostMapping("/import")
    @Transactional
    public ResponseEntity<?> bulkImport(@RequestBody Map<String, Object> body,
                                        HttpSession session) {
        User user = resolveUser(session);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Not logged in."));
        }

        Object rawItems = body != null ? body.get("items") : null;
        if (!(rawItems instanceof List<?>) || ((List<?>) rawItems).isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "No items to import."));
        }

        // Re-serialize through ObjectMapper so each item is a JsonNode we can
        // introspect by kind. (LinkedHashMap values come in as plain maps.)
        JsonNode itemsNode = mapper.valueToTree(rawItems);

        // ── Pre-validate every item. Collect ALL errors so the frontend can
        // surface them at once rather than one failed row at a time.
        List<Map<String, Object>> errors = new ArrayList<>();
        List<TaskRequest> tasksToCreate  = new ArrayList<>();
        List<ExamRequest> examsToCreate  = new ArrayList<>();

        int idx = 0;
        for (Iterator<JsonNode> it = itemsNode.elements(); it.hasNext(); idx++) {
            JsonNode node = it.next();
            String kind = node.path("kind").asText("").toLowerCase();
            try {
                switch (kind) {
                    case "task":
                        TaskRequest tr = buildTaskRequest(node);
                        tasksToCreate.add(tr);
                        break;
                    case "exam":
                        ExamRequest er = buildExamRequest(node);
                        examsToCreate.add(er);
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown kind: " + kind);
                }
            } catch (IllegalArgumentException iae) {
                errors.add(Map.of("index", idx, "error", iae.getMessage()));
            }
        }

        if (!errors.isEmpty()) {
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("tasksCreated", 0);
            resp.put("examsCreated", 0);
            resp.put("errors",       errors);
            // Transaction will roll back automatically (nothing written yet).
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(resp);
        }

        // ── All valid — create in order. If a service throws after this
        // point (DB constraint, unexpected NPE) the @Transactional rolls
        // everything back and the client gets a 500.
        int tasksCreated = 0;
        for (TaskRequest tr : tasksToCreate) {
            taskService.create(user, tr);
            tasksCreated++;
        }
        int examsCreated = 0;
        for (ExamRequest er : examsToCreate) {
            examService.create(user, er);
            examsCreated++;
        }

        log.info("[BULK] userId={} imported tasks={} exams={}",
                user.getId(), tasksCreated, examsCreated);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("tasksCreated", tasksCreated);
        resp.put("examsCreated", examsCreated);
        resp.put("errors",       List.of());
        return ResponseEntity.ok(resp);
    }

    // ── Item → DTO helpers (throw IllegalArgumentException on validation fail) ─

    private TaskRequest buildTaskRequest(JsonNode node) {
        String title = requireText(node, "title");
        String dueS  = requireText(node, "dueDate");
        java.time.LocalDate due;
        try { due = java.time.LocalDate.parse(dueS); }
        catch (Exception e) { throw new IllegalArgumentException("Invalid dueDate."); }

        TaskRequest tr = new TaskRequest();
        tr.setTitle(title);
        tr.setDueDate(due);
        tr.setCourse(optText(node, "course"));
        tr.setNotes(optText(node, "notes"));

        // taskType → TaskType enum. Default OTHER on missing/invalid.
        String tt = optText(node, "taskType");
        if (tt == null) tt = optText(node, "type");   // tolerate either key name
        if (tt != null) {
            try { tr.setType(StudySyncer.entity.TaskType.valueOf(tt)); }
            catch (IllegalArgumentException e) { /* leave default (null → service fills OTHER) */ }
        }
        // priority optional
        String pr = optText(node, "priority");
        if (pr != null) {
            try { tr.setPriority(StudySyncer.entity.Priority.valueOf(pr)); }
            catch (IllegalArgumentException e) { /* service fills default */ }
        }
        return tr;
    }

    private ExamRequest buildExamRequest(JsonNode node) {
        String title = requireText(node, "title");
        String dtS   = requireText(node, "dateTime");
        java.time.Instant instant;
        try {
            instant = java.time.OffsetDateTime.parse(dtS).toInstant();
        } catch (Exception first) {
            try {
                instant = java.time.LocalDateTime.parse(dtS)
                        .atZone(TrackerService.TORONTO).toInstant();
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid dateTime.");
            }
        }

        ExamRequest er = new ExamRequest();
        er.setTitle(title);
        er.setDateTime(instant);
        er.setCourse(optText(node, "course"));
        er.setMaterial(optText(node, "material"));
        er.setLocation(optText(node, "location"));
        er.setNotes(optText(node, "notes"));
        return er;
    }

    private static String requireText(JsonNode node, String field) {
        JsonNode n = node.path(field);
        if (n.isMissingNode() || n.isNull()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        String raw = n.asText("").trim();
        if (raw.isEmpty()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return raw;
    }

    private static String optText(JsonNode node, String field) {
        JsonNode n = node.path(field);
        if (n.isMissingNode() || n.isNull()) return null;
        String raw = n.asText("").trim();
        return raw.isEmpty() ? null : raw;
    }

    private User resolveUser(HttpSession session) {
        Long id = AuthController.resolveUserId(session);
        if (id == null) return null;
        return userService.findById(id).orElse(null);
    }
}
