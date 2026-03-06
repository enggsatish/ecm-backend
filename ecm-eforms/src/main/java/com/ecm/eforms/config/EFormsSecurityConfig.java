package com.ecm.eforms.config;

import com.ecm.common.config.AudienceValidator;
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
 * Security configuration for ecm-eforms.
 *
 * WHY ecm-eforms has its own SecurityConfig instead of using ecm-common's:
 * ─────────────────────────────────────────────────────────────────────────
 * One endpoint must be publicly accessible without a JWT:
 *
 *   POST /api/eforms/docusign/webhook
 *
 * DocuSign Connect authenticates via HMAC (X-DocuSign-Signature-1 header),
 * not a Bearer token. ecm-common's SecurityConfig has no exceptions —
 * every request requires authentication. This config adds the webhook
 * to the permitAll list while keeping everything else locked down.
 *
 * CORS INTENTIONALLY DISABLED
 * ─────────────────────────────
 * Same reason as ecm-common's SecurityConfig — the gateway owns CORS.
 * Having downstream services set Access-Control-Allow-Origin causes
 * duplicate headers and browser CORS errors.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
@RequiredArgsConstructor
public class EFormsSecurityConfig {

    private final EcmJwtConverter ecmJwtConverter;

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String issuerUri;

    @Value("${okta.oauth2.audience}")
    private String audience;

    @Bean
    public SecurityFilterChain eFormsFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // CORS disabled — gateway owns CORS for all external traffic.
                .cors(cors -> cors.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/actuator/health",
                                "/actuator/info",
                                // DocuSign Connect webhook: authenticated by HMAC, not JWT
                                "/api/eforms/docusign/webhook"
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt
                                .decoder(eFormJwtDecoder())
                                .jwtAuthenticationConverter(ecmJwtConverter)
                        )
                );

        return http.build();
    }

    @Bean
    public JwtDecoder eFormJwtDecoder() {
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