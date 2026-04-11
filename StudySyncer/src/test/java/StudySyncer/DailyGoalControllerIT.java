package StudySyncer;

import StudySyncer.entity.DailyGoal;
import StudySyncer.entity.User;
import StudySyncer.repository.DailyGoalRepository;
import StudySyncer.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for DailyGoalController covering the three scenarios that
 * were broken after the accountability/timezone changes:
 *
 *   1. GET /api/daily-goal/today with no (null) tz parameter
 *   2. GET /api/daily-goal/today with an invalid timezone string
 *   3. POST /api/daily-goal on a migrated old row that previously had version = NULL
 *
 * Security notes:
 *   - SecurityConfig already permits all requests and has CSRF disabled,
 *     so requests reach the controller without authentication headers.
 *   - A MockHttpSession with the "userId" attribute set simulates a logged-in
 *     user (matching how AuthController.resolveUserId works).
 *
 * Database notes:
 *   - Uses H2 in-memory DB (application-test.properties, active via @ActiveProfiles).
 *   - @BeforeEach / @AfterEach clean up all rows so tests are independent.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc(addFilters = false)   // bypass servlet filters (session fixation etc.)
@ActiveProfiles("test")
class DailyGoalControllerIT {

    @Autowired private MockMvc             mockMvc;
    @Autowired private UserRepository      userRepo;
    @Autowired private DailyGoalRepository goalRepo;
    @Autowired private JdbcTemplate        jdbc;
    @Autowired private DatabaseInitializer databaseInitializer;

    private User            testUser;
    private MockHttpSession session;

    @BeforeEach
    void setUp() {
        goalRepo.deleteAll();
        userRepo.deleteAll();

        testUser = userRepo.save(buildUser("ctrl_test_user"));

        // Simulate a logged-in session by placing the user's ID in the HTTP session
        // (matches AuthController.SESSION_USER_ID = "userId")
        session = new MockHttpSession();
        session.setAttribute("userId", testUser.getId());
    }

    @AfterEach
    void tearDown() {
        goalRepo.deleteAll();
        userRepo.deleteAll();
    }

    // ── Test 1: GET today with null timezone ───────────────────────────────────

    /**
     * When the tz query parameter is absent the controller must:
     *  - Fall back to "America/Toronto" as the effective timezone
     *  - Return HTTP 200 with a well-formed goal payload
     *  - Return timezone = "America/Toronto" in the response (stored value is null
     *    because we never sent a tz param, so toMap() uses the fallback)
     */
    @Test
    void getToday_withNullTimezone_returns200WithDefaults() throws Exception {
        mockMvc.perform(get("/api/daily-goal/today").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.goalMinutes").value(0))
                .andExpect(jsonPath("$.completedMinutes").value(0))
                .andExpect(jsonPath("$.status").value("none"))
                .andExpect(jsonPath("$.notificationEnabled").value(false))
                .andExpect(jsonPath("$.timezone").value("America/Toronto"))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    // ── Test 2: GET today with invalid timezone string ─────────────────────────

    /**
     * When the browser sends a garbage timezone string (e.g. corrupted JS output)
     * the controller must NOT crash or return 500.  It should:
     *  - Silently fall back to "America/Toronto" via parseTimezone()
     *  - Return HTTP 200
     *  - Return a valid goal payload (no "error" field)
     */
    @Test
    void getToday_withInvalidTimezone_fallsBackAndReturns200() throws Exception {
        mockMvc.perform(get("/api/daily-goal/today")
                        .param("tz", "Not/A_Valid_Zone!!!")
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.goalMinutes").value(0))
                .andExpect(jsonPath("$.timezone").exists())      // some timezone is returned
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    // ── Test 3: POST saveGoal on a migrated old row ────────────────────────────

    /**
     * This test reproduces the exact production failure:
     *
     *   1. A DailyGoal row exists (created before @Version was added).
     *   2. The row has version = NULL in the DB (ddl-auto=update added the column
     *      as nullable; no default value was set).
     *   3. DatabaseInitializer.runStartupMigrations() runs and sets version = 0.
     *   4. A POST to /api/daily-goal arrives.
     *
     * Without the migration, step 4 threw StaleStateException because Hibernate's
     * UPDATE used WHERE version = 0 but the DB stored NULL.  After the migration
     * the UPDATE matches and the save succeeds.
     *
     * The test verifies:
     *  - HTTP 200 (not 500)
     *  - goalMinutes in the response matches what was sent
     *  - no "error" field in the response
     */
    @Test
    void saveGoal_onMigratedOldRow_returns200() throws Exception {
        // Arrange: create a goal row that simulates the pre-migration state
        DailyGoal existing = new DailyGoal();
        existing.setUser(testUser);
        existing.setGoalDate(LocalDate.now(ZoneId.of("America/Toronto")));
        existing.setGoalMinutes(30);
        existing = goalRepo.save(existing);

        // Force version = NULL at the DB level (bypass JPA)
        jdbc.update("UPDATE daily_goals SET version = NULL WHERE id = ?", existing.getId());

        // Run the startup migration (as would happen automatically at server start)
        databaseInitializer.runStartupMigrations();

        // Act: POST a new goal value for today
        String requestBody = """
                {
                  "goalMinutes": 60,
                  "notificationEnabled": false,
                  "accountabilityEmail": "",
                  "timezone": "America/Toronto"
                }
                """;

        mockMvc.perform(post("/api/daily-goal")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                // Assert
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.goalMinutes").value(60))
                .andExpect(jsonPath("$.status").exists())
                .andExpect(jsonPath("$.error").doesNotExist())
                .andExpect(jsonPath("$.code").doesNotExist());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private User buildUser(String username) {
        User u = new User();
        u.setUsername(username);
        u.setEmail(username + "@test.example");
        u.setPasswordHash("$2a$10$placeholder_hash_value_for_tests");
        u.setEmailVerified(true);
        return u;
    }
}
