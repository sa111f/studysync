package StudySyncer.repository;

import StudySyncer.entity.EmailSendLog;
import StudySyncer.entity.EmailType;
import StudySyncer.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
public interface EmailSendLogRepository extends JpaRepository<EmailSendLog, Long> {

    /**
     * Primary idempotency check — NotificationScheduler consults this
     * BEFORE composing the email, so duplicate sends are cheap to avoid.
     * The unique constraint is a defence-in-depth guard against race
     * conditions (two scheduler threads, restart-during-send, etc.).
     */
    boolean existsByUserAndEmailTypeAndReferenceId(User user, EmailType emailType, Long referenceId);

    /**
     * Hard daily cap (spec 8.9): count emails this user has received in
     * the last 24h so the scheduler can skip over-sending regardless of
     * opt-in state. Protects against bugs in the other scheduling logic.
     */
    long countByUserAndSentAtAfter(User user, Instant cutoff);
}
