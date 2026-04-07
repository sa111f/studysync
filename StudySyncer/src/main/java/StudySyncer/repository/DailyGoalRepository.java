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
     * Used by the end-of-day scheduler: fetch every record for today where
     * the user opted in, gave consent, and the SMS hasn't been sent yet.
     */
    List<DailyGoal> findAllByGoalDateAndNotificationEnabledTrueAndConsentConfirmedTrueAndNotificationSentFalse(
            LocalDate goalDate);
}
