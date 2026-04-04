package StudySyncer.repository;

import StudySyncer.entity.SavedPlan;
import StudySyncer.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SavedPlanRepository extends JpaRepository<SavedPlan, Long> {
    Optional<SavedPlan> findByUser(User user);
    List<SavedPlan> findAllByUser(User user);
}
