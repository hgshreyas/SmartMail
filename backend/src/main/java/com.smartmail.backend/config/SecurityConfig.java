package com.smartmail.backend.config;

import java.util.List;
import java.util.function.Consumer;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ClientRegistrationRepository clientRegistrationRepository)
            throws Exception {

        http
                /*
                 * Allow requests from the React frontend.
                 */
                .cors(cors -> {
                })

                /*
                 * React sends POST requests for Keep/Trash.
                 * CSRF is disabled because SmartMail uses
                 * OAuth2 for Gmail access rather than
                 * browser form authentication.
                 */
                .csrf(csrf -> csrf.disable())

                .authorizeHttpRequests(auth -> auth
                        .anyRequest().permitAll()
                )

                .formLogin(form -> {
                })

                /*
                 * Google OAuth login.
                 *
                 * SmartMail performs long-running Gmail operations.
                 * Therefore we explicitly request:
                 *
                 * access_type=offline
                 *     -> asks Google for a refresh token.
                 *
                 * prompt=consent
                 *     -> forces the Google consent screen so an account
                 *        that previously authorized SmartMail can receive
                 *        a refresh token.
                 *
                 * include_granted_scopes=true
                 *     -> preserves previously granted permissions.
                 */
                .oauth2Login(oauth -> oauth
                        .authorizationEndpoint(
                                authorization -> authorization
                                        .authorizationRequestResolver(
                                                authorizationRequestResolver(
                                                        clientRegistrationRepository
                                                )
                                        )
                        )
                );

        return http.build();
    }


    // ============================================================
    // GOOGLE OAUTH AUTHORIZATION REQUEST
    // ============================================================

    private OAuth2AuthorizationRequestResolver authorizationRequestResolver(
            ClientRegistrationRepository clientRegistrationRepository) {

        DefaultOAuth2AuthorizationRequestResolver resolver =
                new DefaultOAuth2AuthorizationRequestResolver(
                        clientRegistrationRepository,
                        "/oauth2/authorization"
                );

        resolver.setAuthorizationRequestCustomizer(
                authorizationRequestCustomizer()
        );

        return resolver;
    }


    // ============================================================
    // REQUEST GOOGLE OFFLINE ACCESS
    // ============================================================

    private Consumer<OAuth2AuthorizationRequest.Builder>
    authorizationRequestCustomizer() {

        return customizer ->
                customizer.additionalParameters(
                        parameters -> {

                            parameters.put(
                                    "access_type",
                                    "offline"
                            );

                            parameters.put(
                                    "prompt",
                                    "consent"
                            );

                            parameters.put(
                                    "include_granted_scopes",
                                    "true"
                            );
                        }
                );
    }


    // ============================================================
    // CORS
    // ============================================================

    /*
     * Allowed React frontends:
     *
     * Local development:
     * http://localhost:5173
     *
     * Production Vercel deployment:
     * https://smart-mail-ro8zxg3sz-smart-mail.vercel.app
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {

        CorsConfiguration configuration =
                new CorsConfiguration();

        configuration.setAllowedOrigins(
                List.of(
                        "http://localhost:5173",
                        "https://smart-mail-ro8zxg3sz-smart-mail.vercel.app"
                )
        );

        configuration.setAllowedMethods(
                List.of(
                        "GET",
                        "POST",
                        "PUT",
                        "DELETE",
                        "OPTIONS"
                )
        );

        configuration.setAllowedHeaders(
                List.of("*")
        );

        /*
         * Required because the React frontend uses:
         *
         * credentials: "include"
         *
         * for OAuth-backed backend requests.
         */
        configuration.setAllowCredentials(
                true
        );

        UrlBasedCorsConfigurationSource source =
                new UrlBasedCorsConfigurationSource();

        source.registerCorsConfiguration(
                "/**",
                configuration
        );

        return source;
    }
}