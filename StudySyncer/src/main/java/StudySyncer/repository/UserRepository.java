package StudySyncer.repository;

import StudySyncer.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    Optional<User> findByUsernameIgnoreCase(String username);

    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByUsername(String username);

    boolean existsByUsernameIgnoreCase(String username);

    boolean existsByEmailIgnoreCase(String email);

    /**
     * Phase 8 — scheduler precheck. Returns users who MIGHT receive any
     * of the three new email types this tick. Each job still evaluates
     * per-user (tz window, idempotency, daily cap) but this query is the
     * cheap early-exit that avoids scanning the entire users table.
     *
     * Excludes users who:
     *   - haven't set an accountability email (explicit per-user gate)
     *   - toggled ALL three notification types off
     *
     * Note: the older {@code notificationEnabled} flag used to gate the
     * goal emails is NOT checked here — Phase 8 types have their own
     * per-type toggle, independent of the pre-existing goal flow.
     */
    @Query("SELECT u FROM User u WHERE u.accountabilityEmail IS NOT NULL " +
           "AND u.accountabilityEmail <> '' " +
           "AND (u.digestEnabled = true " +
           "  OR u.overdueReminderEnabled = true " +
           "  OR u.examReminderEnabled = true)")
    List<User> findNotificationRecipients();
}
