package StudySyncer.repository;

import StudySyncer.entity.StudySession;
import StudySyncer.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface StudySessionRepository extends JpaRepository<StudySession, Long> {

    /** Sessions for a user within a date range, newest first. */
    List<StudySession> findByUserAndStudyDateBetweenOrderByCompletedAtDesc(
            User user, LocalDate from, LocalDate to);

    /** All sessions for a user, newest first (used for streak calculation). */
    List<StudySession> findByUserOrderByCompletedAtDesc(User user);
}
