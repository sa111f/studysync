package StudySyncer;

import StudySyncer.dto.TimerStateDto;
import StudySyncer.entity.TimerProgress;
import StudySyncer.entity.User;
import StudySyncer.repository.TimerProgressRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * STUD-34 — Save timer mode + session count for logged-in users.
 * STUD-35 — Load saved timer state on return.
 * Guests are unaffected: endpoints return 401 silently when not logged in.
 */
@RestController
@RequestMapping("/api/timer")
public class TimerSaveController {

    private final TimerProgressRepository timerRepo;
    private final UserService userService;

    public TimerSaveController(TimerProgressRepository timerRepo, UserService userService) {
        this.timerRepo   = timerRepo;
        this.userService = userService;
    }

    /** POST /api/timer/save */
    @PostMapping("/save")
    @Transactional
    public ResponseEntity<?> save(@RequestBody TimerStateDto dto, HttpSession session) {
        User user = resolveUser(session);
        if (user == null) return ResponseEntity.status(401).body(Map.of("error", "Not logged in."));

        TimerProgress p = timerRepo.findByUser(user).orElse(new TimerProgress());
        p.setUser(user);
        p.setMode(dto.getMode());
        p.setTotalSeconds(dto.getTotalSeconds());
        p.setSessionCount(dto.getSessionCount());
        p.setUpdatedAt(LocalDateTime.now());
        timerRepo.save(p);

        return ResponseEntity.ok(Map.of("message", "Timer saved."));
    }

    /** GET /api/timer/load */
    @GetMapping("/load")
    public ResponseEntity<?> load(HttpSession session) {
        User user = resolveUser(session);
        if (user == null) return ResponseEntity.status(401).body(Map.of("error", "Not logged in."));

        return timerRepo.findByUser(user)
                .<ResponseEntity<?>>map(p -> {
                    TimerStateDto dto = new TimerStateDto();
                    dto.setMode(p.getMode());
                    dto.setTotalSeconds(p.getTotalSeconds());
                    dto.setSessionCount(p.getSessionCount());
                    return ResponseEntity.ok(dto);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    private User resolveUser(HttpSession session) {
        Long id = AuthController.resolveUserId(session);
        if (id == null) return null;
        return userService.findById(id).orElse(null);
    }
}
