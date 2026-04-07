package StudySyncer.repository;

import StudySyncer.entity.Deadline;
import StudySyncer.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DeadlineRepository extends JpaRepository<Deadline, Long> {

    List<Deadline> findByUserOrderByDueDateAsc(User user);
}
