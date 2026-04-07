package StudySyncer.repository;

import StudySyncer.entity.StudySession;
import StudySyncer.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface StudySessionRepository extends JpaRepository<StudySession, Long> {

    /** Sessions for a user within a date range, newest first. */
    List<StudySession> findByUserAndStudyDateBetweenOrderByCompletedAtDesc(
            User user, LocalDate from, LocalDate to);

    /** All sessions for a user, newest first (used for streak calculation). */
    List<StudySession> findByUserOrderByCompletedAtDesc(User user);

    /**
     * Sum of durationMinutes for all sessions on a specific date, regardless of
     * the completed flag.  Returns 0 (via COALESCE) when no sessions exist.
     * This is the authoritative "how much did the user study today" figure and
     * must match exactly what the Study Tracker's Today view shows.
     */
    @Query("SELECT COALESCE(SUM(s.durationMinutes), 0) FROM StudySession s " +
           "WHERE s.user = :user AND s.studyDate = :date")
    long sumDurationByUserAndDate(@Param("user") User user, @Param("date") LocalDate date);
}
