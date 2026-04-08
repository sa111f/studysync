package StudySyncer.repository;

import StudySyncer.entity.DailyGoal;
import StudySyncer.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface DailyGoalRepository extends JpaRepository<DailyGoal, Long> {

    Optional<DailyGoal> findByUserAndGoalDate(User user, LocalDate goalDate);

    /**
     * Used by the end-of-day email scheduler: fetch every record for today where
     * the user enabled email accountability, the email alert hasn't been sent yet,
     * and the user actually set a real goal (> 0 min).
     *
     * The scheduler checks whether the goal was missed before sending.
     */
    List<DailyGoal> findAllByGoalDateAndNotificationEnabledTrueAndEmailAlertSentFalseAndGoalMinutesGreaterThan(
            LocalDate goalDate, int goalMinutes);
}
