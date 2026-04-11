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
     * Used by the end-of-day (23:59) scheduler to find records for a specific date
     * that still need a missed-goal email evaluation.
     *
     * Conditions (all must be true):
     *   - goalDate matches the provided date
     *   - user enabled email accountability (notificationEnabled = true)
     *   - missed-goal email not yet sent (emailAlertSent = false)
     *   - goal-reached email not yet sent (goalReachedEmailSent = false)
     *   - a real goal was set (goalMinutes > 0)
     */
    List<DailyGoal> findAllByGoalDateAndNotificationEnabledTrueAndEmailAlertSentFalseAndGoalReachedEmailSentFalseAndGoalMinutesGreaterThan(
            LocalDate goalDate, int goalMinutes);

    /**
     * Used by the rollover service on request paths (dashboard load, session save, etc.)
     * to find past goals for a SPECIFIC USER that still need missed-goal evaluation.
     *
     * "Before" means goalDate strictly before cutoffDate (i.e., in the past relative
     * to the user's current local date, so midnight has already passed for that day).
     */
    List<DailyGoal> findAllByUserAndGoalDateBeforeAndNotificationEnabledTrueAndEmailAlertSentFalseAndGoalReachedEmailSentFalseAndGoalMinutesGreaterThan(
            User user, LocalDate cutoffDate, int goalMinutes);

    /**
     * Used by the 15-minute periodic scheduler to find past goals across ALL USERS
     * that still need missed-goal evaluation.
     *
     * The cutoffDate should be "tomorrow UTC" so goals from any user timezone are included.
     * Per-user timezone refinement is applied in the rollover service before sending.
     */
    List<DailyGoal> findAllByGoalDateBeforeAndNotificationEnabledTrueAndEmailAlertSentFalseAndGoalReachedEmailSentFalseAndGoalMinutesGreaterThan(
            LocalDate cutoffDate, int goalMinutes);
}
