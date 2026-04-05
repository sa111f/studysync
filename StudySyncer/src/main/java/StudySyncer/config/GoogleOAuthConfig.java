package StudySyncer.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;

/**
 * Provides a custom {@link ClientRegistrationRepository} bean, which overrides
 * Spring Boot's auto-configuration ({@code @ConditionalOnMissingBean}).
 *
 * When {@code GOOGLE_CLIENT_ID} / {@code GOOGLE_CLIENT_SECRET} env vars are blank
 * (not configured yet), this returns a no-op repository instead of crashing the app
 * with {@code IllegalArgumentException: clientId cannot be empty}.
 *
 * When the env vars are present, it builds a real Google registration.
 */
@Configuration
public class GoogleOAuthConfig {

    @Bean
    public ClientRegistrationRepository clientRegistrationRepository(
            @Value("${spring.security.oauth2.client.registration.google.client-id:}") String clientId,
            @Value("${spring.security.oauth2.client.registration.google.client-secret:}") String clientSecret) {

        if (clientId.isBlank() || clientSecret.isBlank()) {
            // Safe empty repository — findByRegistrationId("google") returns null.
            // OAuth2AuthorizationRequestRedirectFilter will catch the resulting
            // OAuth2AuthorizationRequestException and invoke the failure handler
            // (configured in SecurityConfig as failureUrl("/?error=oauth")).
            return registrationId -> null;
        }

        ClientRegistration google = ClientRegistration
                .withRegistrationId("google")
                .clientId(clientId)
                .clientSecret(clientSecret)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope("openid", "profile", "email")
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .tokenUri("https://www.googleapis.com/oauth2/v4/token")
                .userInfoUri("https://www.googleapis.com/oauth2/v3/userinfo")
                .userNameAttributeName(IdTokenClaimNames.SUB)
                .jwkSetUri("https://www.googleapis.com/oauth2/v3/certs")
                .clientName("Google")
                .build();

        return new InMemoryClientRegistrationRepository(google);
    }
}
