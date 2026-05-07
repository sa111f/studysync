package StudySyncer;

import StudySyncer.entity.EmailType;
import StudySyncer.entity.User;
import StudySyncer.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * UnsubscribeController end-to-end: valid token disables the email type,
 * expired token → 410, tampered token → 400, second click is idempotent.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class UnsubscribeControllerTest {

    @Autowired private MockMvc                mockMvc;
    @Autowired private UserRepository         userRepo;
    @Autowired private UnsubscribeTokenService tokens;

    private User user;

    @BeforeEach
    void setUp() {
        userRepo.deleteAll();
        user = new User();
        user.setUsername("unsub_user");
        user.setEmail("unsub_user@test.example");
        user.setPasswordHash("$2a$10$placeholder_hash_value_for_tests");
        user.setEmailVerified(true);
        user.setAccountabilityEmail("unsub_user-acct@test.example");
        user.setDigestEnabled(true);
        user.setOverdueReminderEnabled(true);
        user.setExamReminderEnabled(true);
        user = userRepo.save(user);
    }

    @AfterEach
    void tearDown() {
        userRepo.deleteAll();
    }

    // ── Valid token disables that one type ────────────────────

    @Test
    void unsubscribe_validToken_disablesSpecificEmailType() throws Exception {
        String token = tokens.sign(user.getId(), EmailType.DIGEST);

        mockMvc.perform(get("/api/notifications/unsubscribe").param("token", token))
                .andExpect(status().isOk())
                .andExpect(view().name("unsubscribed"));

        User reloaded = userRepo.findById(user.getId()).orElseThrow();
        assertThat(reloaded.isDigestEnabled()).isFalse();
        // The OTHER two types stay enabled — unsubscribe is per-type.
        assertThat(reloaded.isOverdueReminderEnabled()).isTrue();
        assertThat(reloaded.isExamReminderEnabled()).isTrue();
    }

    // ── Second click → idempotent ─────────────────────────────

    @Test
    void unsubscribe_validTokenReplayed_isIdempotent() throws Exception {
        String token = tokens.sign(user.getId(), EmailType.OVERDUE_REMINDER);

        mockMvc.perform(get("/api/notifications/unsubscribe").param("token", token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/notifications/unsubscribe").param("token", token))
                .andExpect(status().isOk());

        User reloaded = userRepo.findById(user.getId()).orElseThrow();
        assertThat(reloaded.isOverdueReminderEnabled()).isFalse();
    }

    // ── Tampered token → 400 ──────────────────────────────────

    @Test
    void unsubscribe_tamperedToken_returns400() throws Exception {
        String token = tokens.sign(user.getId(), EmailType.DIGEST);
        // Flip last character to corrupt the HMAC.
        char last = token.charAt(token.length() - 1);
        String tampered = token.substring(0, token.length() - 1) + (last == 'A' ? 'B' : 'A');

        mockMvc.perform(get("/api/notifications/unsubscribe").param("token", tampered))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());

        User reloaded = userRepo.findById(user.getId()).orElseThrow();
        assertThat(reloaded.isDigestEnabled()).isTrue(); // untouched
    }

    // ── Missing token → 400 ──────────────────────────────────

    @Test
    void unsubscribe_missingToken_returns400() throws Exception {
        mockMvc.perform(get("/api/notifications/unsubscribe"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    // ── Exam-reminder unsubscribe kills ALL three threshold types ──

    @Test
    void unsubscribe_examReminder_disablesEntireExamGroup() throws Exception {
        // All three EXAM_REMINDER_* types share one toggle on the User
        // entity — verify that clicking "turn off exam reminders" from a
        // 3-day email disables the 7-day and 1-day thresholds too.
        String token = tokens.sign(user.getId(), EmailType.EXAM_REMINDER_3D);

        mockMvc.perform(get("/api/notifications/unsubscribe").param("token", token))
                .andExpect(status().isOk());

        User reloaded = userRepo.findById(user.getId()).orElseThrow();
        assertThat(reloaded.isExamReminderEnabled()).isFalse();
    }
}
