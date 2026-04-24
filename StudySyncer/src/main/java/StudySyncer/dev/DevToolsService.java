package StudySyncer.dev;

import StudySyncer.entity.User;
import StudySyncer.repository.StudySessionRepository;
import StudySyncer.repository.TimerProgressRepository;
import StudySyncer.repository.UserRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * DEV ONLY — provides user inspection and bulk-delete utilities.
 * Not loaded outside the "dev" Spring profile.
 */
@Service
@Profile("dev")
public class DevToolsService {

    private final UserRepository           userRepository;
    private final StudySessionRepository   studySessionRepository;
    private final TimerProgressRepository  timerProgressRepository;

    public DevToolsService(UserRepository          userRepository,
                           StudySessionRepository  studySessionRepository,
                           TimerProgressRepository timerProgressRepository) {
        this.userRepository          = userRepository;
        this.studySessionRepository  = studySessionRepository;
        this.timerProgressRepository = timerProgressRepository;
    }

    // ── List ──────────────────────────────────────────────

    public List<User> findAllUsers() {
        return userRepository.findAll(Sort.by(Sort.Direction.ASC, "id"));
    }

    // ── Delete one ────────────────────────────────────────

    /**
     * Deletes a single user and all data that belongs to them.
     *
     * @throws NoSuchElementException if the username does not exist.
     */
    @Transactional
    public void deleteUser(String username) {
        User user = userRepository.findByUsername(username)
            .orElseThrow(() -> new NoSuchElementException(
                "No user found with username '" + username + "'."));
        purgeUserData(user);
        userRepository.delete(user);
    }

    // ── Reset all ─────────────────────────────────────────

    /**
     * Wipes every user and all related data from the database.
     * Deletion order respects foreign-key constraints.
     */
    @Transactional
    public int resetAllUsers() {
        int count = (int) userRepository.count();
        if (count == 0) return 0;

        // Child tables with direct FK to users (no cascade from User side)
        studySessionRepository.deleteAllInBatch();
        timerProgressRepository.deleteAllInBatch();

        // Users (all FKs now gone)
        userRepository.deleteAllInBatch();

        return count;
    }

    // ── Private helper ────────────────────────────────────

    /**
     * Deletes all data owned by one user, in FK-safe order.
     * Does NOT delete the User row itself — caller does that.
     */
    private void purgeUserData(User user) {
        studySessionRepository.deleteAll(
            studySessionRepository.findByUserOrderByCompletedAtDesc(user));
        timerProgressRepository.findByUser(user)
            .ifPresent(timerProgressRepository::delete);
    }
}
