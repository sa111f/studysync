package StudySyncer.repository;

import StudySyncer.entity.TimerProgress;
import StudySyncer.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TimerProgressRepository extends JpaRepository<TimerProgress, Long> {
    Optional<TimerProgress> findByUser(User user);
}
