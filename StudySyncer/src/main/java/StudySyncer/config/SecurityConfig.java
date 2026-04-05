package StudySyncer.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security configuration.
 *
 * StudySyncer manages its own session-based auth via AuthController + HttpSession.
 * Spring Security handles:
 *   1. BCryptPasswordEncoder bean (password hashing in UserService).
 *   2. Google OAuth2 login — success is bridged to the custom session via OAuth2SuccessHandler.
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

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           OAuth2SuccessHandler oauth2SuccessHandler) throws Exception {
        http
            .formLogin(form  -> form.disable())
            .httpBasic(basic -> basic.disable())
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .csrf(csrf -> csrf.disable())
            .oauth2Login(oauth -> oauth
                .successHandler(oauth2SuccessHandler)
            );

        return http.build();
    }
}
