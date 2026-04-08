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
     * Used by the end-of-day scheduler to find records that need a missed-goal email.
     *
     * Conditions (all must be true):
     *   - goalDate matches today
     *   - user enabled email accountability (notificationEnabled = true)
     *   - missed-goal email not yet sent (emailAlertSent = false)
     *   - goal-reached email not yet sent (goalReachedEmailSent = false)
     *     → if goal was reached, no missed-goal email should be sent
     *   - a real goal was set (goalMinutes > 0)
     *
     * The scheduler still checks completedMinutes vs goalMinutes before sending,
     * in case a record slips through with the goal actually met.
     */
    List<DailyGoal> findAllByGoalDateAndNotificationEnabledTrueAndEmailAlertSentFalseAndGoalReachedEmailSentFalseAndGoalMinutesGreaterThan(
            LocalDate goalDate, int goalMinutes);
}
