package com.ecm.common.config;

import com.ecm.common.security.EcmJwtConverter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Shared Spring Security configuration for all downstream ECM services:
 * ecm-identity, ecm-document, ecm-workflow, ecm-admin, ecm-eforms.
 *
 * CORS IS INTENTIONALLY DISABLED HERE
 * ─────────────────────────────────────
 * All external traffic reaches downstream services ONLY via ecm-gateway.
 * The gateway owns CORS platform-wide in GatewaySecurityConfig.
 *
 * If downstream services also set Access-Control-Allow-Origin, the header
 * appears twice in the response (once from the service, once from the
 * gateway). Browsers enforce "only one value allowed" and block the request:
 *
 *   "The 'Access-Control-Allow-Origin' header contains multiple values
 *    'http://localhost:3000, http://localhost:3000', but only one is allowed."
 *
 * The previous corsConfigurationSource() bean and .cors(...) wiring are
 * removed. Downstream services set no CORS headers — the gateway handles it.
 *
 * Direct service access (bypassing the gateway) is only used in local dev
 * via Vite's proxy (/api → http://localhost:8081), which does not trigger
 * browser CORS checks because it appears as a same-origin request.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final EcmJwtConverter ecmJwtConverter;

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String issuerUri;

    @Value("${okta.oauth2.audience}")
    private String audience;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // CORS disabled — gateway owns CORS for all external traffic.
                // Enabling it here causes duplicate Access-Control-Allow-Origin
                // headers which browsers reject.
                .cors(cors -> cors.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/actuator/health",
                                "/actuator/info"
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt
                                .decoder(jwtDecoder())
                                .jwtAuthenticationConverter(ecmJwtConverter)
                        )
                );

        return http.build();
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder = JwtDecoders.fromIssuerLocation(issuerUri);

        OAuth2TokenValidator<Jwt> issuerValidator =
                JwtValidators.createDefaultWithIssuer(issuerUri);

        OAuth2TokenValidator<Jwt> audienceValidator =
                new AudienceValidator(audience);

        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(issuerValidator, audienceValidator)
        );

        return decoder;
    }
}