package StudySyncer;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Startup schema migration for the daily_goals.version column.
 *
 * WHY THIS EXISTS
 * ───────────────
 * The @Version field was introduced after the daily_goals table already existed
 * in production. Hibernate's ddl-auto=update adds the column as NULLABLE with no
 * DEFAULT, leaving every pre-existing row with version = NULL.
 *
 * Hibernate's optimistic-lock UPDATE uses:
 *   WHERE id = ? AND version = 0
 * (getLong() returns 0 for SQL NULL)
 *
 * PostgreSQL evaluates "version = 0" as FALSE when the stored value is NULL, so
 * the UPDATE matches 0 rows and Hibernate throws StaleStateException — which the
 * goal-save endpoint cannot distinguish from a real concurrent conflict, resulting
 * in a 500 for every user who has a pre-migration goal row.
 *
 * MIGRATION STEPS (each wrapped and logged individually)
 * ───────────────────────────────────────────────────────
 * 1. UPDATE daily_goals SET version = 0 WHERE version IS NULL
 *    Backfills every affected row so WHERE version = 0 matches correctly.
 *
 * 2. ALTER TABLE daily_goals ALTER COLUMN version SET DEFAULT 0
 *    Ensures the DB-level default is 0 so future manual inserts that bypass JPA
 *    also get a safe starting version.
 *
 * 3. ALTER TABLE daily_goals ALTER COLUMN version SET NOT NULL
 *    Enforces the invariant at the DB level now that all rows have a non-null value.
 *    Safe because Step 1 just cleared every NULL.
 *
 * IDEMPOTENCY
 * ───────────
 * Each step is idempotent:
 *  - The UPDATE is a no-op when no NULL rows exist.
 *  - The ALTER DEFAULT is a no-op when the default is already 0.
 *  - The ALTER NOT NULL is a no-op when the column is already NOT NULL.
 * Running this on every restart is safe and adds < 1 ms per startup after the
 * first run.
 *
 * RELATIONSHIP TO @Column(columnDefinition = "BIGINT NOT NULL DEFAULT 0")
 * ────────────────────────────────────────────────────────────────────────
 * The @Column columnDefinition guarantees that brand-new schemas (created via
 * ddl-auto=create or ddl-auto=create-drop) get the right definition from the start.
 * This migration handles existing schemas where ddl-auto=update already added the
 * column as nullable — something the @Column annotation cannot retroactively fix.
 */
@Component
public class DatabaseInitializer {

    private static final Logger log = LoggerFactory.getLogger(DatabaseInitializer.class);

    @PersistenceContext
    private EntityManager em;

    /**
     * Entry point: fires once after the Spring context is fully ready and all JPA
     * schema updates have completed.  Also called directly from integration tests
     * to replay the migration against test data.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void runStartupMigrations() {
        migrateVersionColumn();
    }

    // ── Migration: daily_goals.version ───────────────────────────────────────────

    private void migrateVersionColumn() {

        // ── Step 1: Backfill NULLs ──────────────────────────────────────────────
        //
        // This MUST run before the NOT NULL constraint is added.  If the UPDATE
        // itself fails (e.g. column doesn't exist yet on a brand-new deployment),
        // the subsequent ALTER statements are meaningless — return early.
        int backfilled;
        try {
            backfilled = em.createNativeQuery(
                            "UPDATE daily_goals SET version = 0 WHERE version IS NULL")
                    .executeUpdate();

            if (backfilled > 0) {
                log.info("[MIGRATE] daily_goals.version: backfilled {} NULL row(s) to 0 " +
                        "— these rows were created before @Version was introduced", backfilled);
            } else {
                log.debug("[MIGRATE] daily_goals.version: no NULL rows found — schema is current");
            }
        } catch (Exception e) {
            // Column may not exist yet on the very first deployment of this schema.
            // Hibernate will create it correctly via ddl-auto on the next restart,
            // at which point this migration becomes a no-op.
            log.warn("[MIGRATE] daily_goals.version: UPDATE step skipped — " +
                    "column may not exist yet (expected on first-ever startup): {}", e.getMessage());
            return;
        }

        // ── Step 2: Set DB-level DEFAULT 0 ─────────────────────────────────────
        //
        // Guards against manual inserts that bypass JPA and would otherwise
        // produce NULL versions.  No-op if the default is already 0.
        try {
            em.createNativeQuery(
                    "ALTER TABLE daily_goals ALTER COLUMN version SET DEFAULT 0")
                    .executeUpdate();
            log.debug("[MIGRATE] daily_goals.version: DEFAULT 0 applied");
        } catch (Exception e) {
            // Acceptable: some dialects (H2 in certain modes) may report "already set"
            // or use slightly different syntax — the backfill in Step 1 is sufficient
            // to unblock Hibernate's optimistic-lock checks.
            log.warn("[MIGRATE] daily_goals.version: ALTER DEFAULT step skipped " +
                    "(may already be applied or dialect variation): {}", e.getMessage());
        }

        // ── Step 3: Enforce NOT NULL ────────────────────────────────────────────
        //
        // Safe because Step 1 just cleared every NULL row.  No-op if already NOT NULL.
        try {
            em.createNativeQuery(
                    "ALTER TABLE daily_goals ALTER COLUMN version SET NOT NULL")
                    .executeUpdate();
            log.debug("[MIGRATE] daily_goals.version: NOT NULL constraint applied");
        } catch (Exception e) {
            log.warn("[MIGRATE] daily_goals.version: ALTER NOT NULL step skipped " +
                    "(may already be applied or dialect variation): {}", e.getMessage());
        }
    }
}
