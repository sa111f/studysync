package StudySyncer.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;

import java.util.Optional;

/**
 * Spring Security configuration.
 *
 * StudySyncer manages its own session-based auth via AuthController + HttpSession.
 * Spring Security handles:
 *   1. BCryptPasswordEncoder bean (password hashing in UserService).
 *   2. Google OAuth2 login — only enabled when real credentials are configured.
 *      If GOOGLE_CLIENT_ID / GOOGLE_CLIENT_SECRET are absent (e.g. on Railway
 *      before credentials are set), oauth2Login is skipped and the app starts
 *      normally. Password login continues to work in all cases.
 *   3. Permitting all HTTP requests — controllers enforce their own access checks.
 *
 * CSRF is disabled because all state-changing calls come from same-origin JS fetches,
 * and the session cookie is SameSite=Lax (Tomcat default).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * @param clientRepo Optional — Spring Boot only auto-creates this bean when
     *                   spring.security.oauth2.client.registration.google.client-id
     *                   is set to a non-blank value. When the property is absent
     *                   (no Google credentials configured) the Optional is empty and
     *                   we simply skip oauth2Login, keeping the app startable.
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           OAuth2SuccessHandler oauth2SuccessHandler,
                                           Optional<ClientRegistrationRepository> clientRepo) throws Exception {
        http
            .formLogin(form  -> form.disable())
            .httpBasic(basic -> basic.disable())
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .csrf(csrf -> csrf.disable());

        if (clientRepo.isPresent()) {
            http.oauth2Login(oauth -> oauth.successHandler(oauth2SuccessHandler));
        }

        return http.build();
    }
}
