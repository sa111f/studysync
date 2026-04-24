package StudySyncer.repository;

import StudySyncer.entity.Exam;
import StudySyncer.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ExamRepository extends JpaRepository<Exam, Long> {

    /** All exams for a user, chronological. */
    List<Exam> findByUserOrderByDateTimeAsc(User user);

    /** Exams whose start time falls within [from, to]. */
    List<Exam> findByUserAndDateTimeBetween(User user, LocalDateTime from, LocalDateTime to);

    /** Upcoming exams — dateTime strictly after `now`, soonest first. */
    List<Exam> findByUserAndDateTimeAfterOrderByDateTimeAsc(User user, LocalDateTime now);
}
