package StudySyncer.repository;

import StudySyncer.entity.Task;
import StudySyncer.entity.TaskStatus;
import StudySyncer.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface TaskRepository extends JpaRepository<Task, Long> {

    /** All tasks for a user, soonest-due first. */
    List<Task> findByUserOrderByDueDateAsc(User user);

    /** Tasks in a given lifecycle state (e.g. COMPLETED). */
    List<Task> findByUserAndStatus(User user, TaskStatus status);

    /** Tasks due within [from, to] (both inclusive). */
    List<Task> findByUserAndDueDateBetween(User user, LocalDate from, LocalDate to);

    /**
     * Overdue tasks: due before `before` AND not in the given status.
     * Typical call: findByUserAndDueDateBeforeAndStatusNot(user, today, COMPLETED).
     */
    List<Task> findByUserAndDueDateBeforeAndStatusNot(User user, LocalDate before, TaskStatus status);

    long countByUserAndStatus(User user, TaskStatus status);
}
