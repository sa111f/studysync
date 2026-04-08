package StudySyncer.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security configuration.
 *
 * GoogleOAuthConfig always supplies a ClientRegistrationRepository bean, so
 * oauth2Login() is always configured here. When Google credentials are absent
 * the repository returns null → Spring Security fires the failureUrl redirect
 * → /?error=oauth → auth.js shows a banner. No white page.
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
            // Explicit session management:
            //   IF_REQUIRED — create a session only when needed (the default, but stated clearly).
            //   migrateSession — on OAuth2 success, copy attributes to a new session ID to prevent
            //                    session fixation. Our "userId" attribute is preserved during migration.
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                .sessionFixation().migrateSession()
            )
            .oauth2Login(oauth -> oauth
                .loginPage("/")
                .successHandler(oauth2SuccessHandler)
                .failureUrl("/?error=oauth")
            );

        return http.build();
    }
}
