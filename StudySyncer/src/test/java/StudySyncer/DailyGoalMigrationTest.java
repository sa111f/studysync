package StudySyncer;

import StudySyncer.entity.DailyGoal;
import StudySyncer.entity.User;
import StudySyncer.repository.DailyGoalRepository;
import StudySyncer.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Integration tests for the DatabaseInitializer migration that patches
 * daily_goals rows where version = NULL.
 *
 * These tests simulate the state of a pre-migration production database:
 * a DailyGoal row that was created before the @Version column was introduced
 * (and therefore has version = NULL in the database).
 *
 * Each test uses JdbcTemplate for direct SQL (bypasses JPA first-level cache)
 * and calls DatabaseInitializer.runStartupMigrations() explicitly to replay
 * the migration against the manipulated test data.
 */
@SpringBootTest
@ActiveProfiles("test")
class DailyGoalMigrationTest {

    @Autowired private DatabaseInitializer   databaseInitializer;
    @Autowired private DailyGoalRepository   goalRepo;
    @Autowired private UserRepository        userRepo;
    @Autowired private JdbcTemplate          jdbc;

    /** Remove all test rows after each test to prevent cross-test contamination. */
    @AfterEach
    void cleanup() {
        goalRepo.deleteAll();  // FK dependency: goals before users
        userRepo.deleteAll();
    }

    // ── Test 1: migration backfills NULL versions ──────────────────────────────

    /**
     * Verifies that runStartupMigrations() sets version = 0 for every row
     * that still has version = NULL in the database.
     */
    @Test
    void runStartupMigrations_backfillsNullVersionsToZero() {
        // Arrange: create a real goal via JPA (gets version = 0 normally)
        User user = userRepo.save(testUser("mig1_user"));
        DailyGoal goal = goalRepo.save(testGoal(user, LocalDate.now(ZoneId.of("America/Toronto"))));

        // Simulate pre-migration state: force version = NULL at the DB level
        jdbc.update("UPDATE daily_goals SET version = NULL WHERE id = ?", goal.getId());

        // Confirm NULL is actually stored (bypasses JPA cache)
        Long vBefore = jdbc.queryForObject(
                "SELECT version FROM daily_goals WHERE id = ?", Long.class, goal.getId());
        assertThat(vBefore).as("version should be NULL before migration").isNull();

        // Act: replay the migration
        databaseInitializer.runStartupMigrations();

        // Assert: version is now 0
        Long vAfter = jdbc.queryForObject(
                "SELECT version FROM daily_goals WHERE id = ?", Long.class, goal.getId());
        assertThat(vAfter).as("migration must set version = 0").isEqualTo(0L);
    }

    // ── Test 2: after migration an old row can be saved without exception ──────

    /**
     * Verifies that a DailyGoal row that had version = NULL can be loaded and
     * saved (UPDATE) after runStartupMigrations() has run.
     *
     * Without the migration, Hibernate generates WHERE version = 0 but the DB
     * stores NULL, so the UPDATE matches 0 rows and throws StaleStateException.
     * After the migration version = 0 matches and the save succeeds.
     */
    @Test
    void afterMigration_oldRowWithNullVersionCanBeSavedWithoutException() {
        // Arrange
        User user   = userRepo.save(testUser("mig2_user"));
        DailyGoal g = goalRepo.save(testGoal(user, LocalDate.now(ZoneId.of("America/Toronto")).minusDays(1)));

        jdbc.update("UPDATE daily_goals SET version = NULL WHERE id = ?", g.getId());

        // Act: migrate, then reload and save
        databaseInitializer.runStartupMigrations();

        // Load in a fresh JPA session (detached after the repository call returns)
        DailyGoal loaded = goalRepo.findById(g.getId()).orElseThrow();
        loaded.setGoalMinutes(90);

        // Assert: saveAndFlush must not throw StaleStateException or any other exception
        assertThatCode(() -> goalRepo.saveAndFlush(loaded))
                .as("save on a migrated row must not throw")
                .doesNotThrowAnyException();

        // Verify the change actually persisted
        DailyGoal saved = goalRepo.findById(g.getId()).orElseThrow();
        assertThat(saved.getGoalMinutes()).isEqualTo(90);
        assertThat(saved.getVersion()).isGreaterThan(0L);  // version was incremented by Hibernate
    }

    // ── Test 3: migration is idempotent ──────────────────────────���─────────────

    /**
     * Verifies that calling runStartupMigrations() multiple times is safe —
     * the second call finds no NULL rows and completes without errors or side-effects.
     */
    @Test
    void runStartupMigrations_isIdempotent() {
        User user = userRepo.save(testUser("mig3_user"));
        goalRepo.save(testGoal(user, LocalDate.now(ZoneId.of("America/Toronto"))));

        // Run twice — second run must not throw and must leave DB in the same state
        assertThatCode(() -> {
            databaseInitializer.runStartupMigrations();
            databaseInitializer.runStartupMigrations();
        }).as("running the migration twice must not throw").doesNotThrowAnyException();
    }

    // ── Helpers ─────────────────��─────────────────────────────────────────────

    private User testUser(String username) {
        User u = new User();
        u.setUsername(username);
        u.setEmail(username + "@test.example");
        u.setPasswordHash("$2a$10$placeholder_hash_value_for_tests");
        u.setEmailVerified(true);
        return u;
    }

    private DailyGoal testGoal(User user, LocalDate date) {
        DailyGoal g = new DailyGoal();
        g.setUser(user);
        g.setGoalDate(date);
        g.setGoalMinutes(30);
        return g;
    }
}
