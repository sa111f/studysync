package StudySyncer;

import StudySyncer.entity.StudySession;
import StudySyncer.entity.User;
import StudySyncer.repository.StudySessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Saves and retrieves completed study sessions.
 *
 * Each session belongs to a user and records what was studied,
 * how long, and when it was completed.
 */
@Service
public class StudySessionService {

    private final StudySessionRepository sessionRepository;

    public StudySessionService(StudySessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    // ── Save ──────────────────────────────────────────────

    /**
     * Records a completed study session for the given user.
     *
     * @param user            the logged-in user
     * @param materialName    what was studied (e.g. "Calculus Ch.3")
     * @param durationMinutes how long the session lasted
     * @param timerMode       "pomodoro", "countdown", or "stopwatch"
     * @param completed       true if the timer ran to completion
     */
    @Transactional
    public StudySession save(User user,
                             String materialName,
                             int durationMinutes,
                             String timerMode,
                             boolean completed) {
        StudySession s = new StudySession();
        s.setUser(user);
        s.setMaterialName((materialName != null && !materialName.isBlank())
                ? materialName.trim() : "General");
        s.setDurationMinutes(durationMinutes);
        s.setTimerMode(timerMode);
        s.setCompleted(completed);

        LocalDateTime now = LocalDateTime.now();
        s.setCompletedAt(now);
        s.setStudyDate(now.toLocalDate());

        return sessionRepository.save(s);
    }

    // ── Query ─────────────────────────────────────────────

    /** All sessions for a user, newest first. */
    @Transactional(readOnly = true)
    public List<StudySession> findAllByUser(User user) {
        return sessionRepository.findByUserOrderByCompletedAtDesc(user);
    }

    /** Sessions within a date range (inclusive), newest first. */
    @Transactional(readOnly = true)
    public List<StudySession> findByUserAndDateRange(User user, LocalDate from, LocalDate to) {
        return sessionRepository.findByUserAndStudyDateBetweenOrderByCompletedAtDesc(user, from, to);
    }
}
