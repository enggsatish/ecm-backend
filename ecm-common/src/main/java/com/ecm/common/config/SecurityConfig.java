package com.ecm.common.config;

import com.ecm.common.config.AudienceValidator;
import com.ecm.common.security.EcmJwtConverter;
import com.ecm.common.security.EcmPermissionEvaluator;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
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
 * Sprint G additions:
 *   - EcmPermissionEvaluator registered as the MethodSecurityExpressionHandler's
 *     PermissionEvaluator.  This enables hasPermission() SpEL expressions in
 *     @PreAuthorize and @RequiresPermission annotations across all services.
 *
 * CORS IS INTENTIONALLY DISABLED HERE.
 * All external traffic reaches downstream services ONLY via ecm-gateway.
 * Enabling CORS here causes duplicate Access-Control-Allow-Origin headers
 * which browsers reject.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final EcmJwtConverter        ecmJwtConverter;
    private final EcmPermissionEvaluator ecmPermissionEvaluator;

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

    /**
     * Register EcmPermissionEvaluator so hasPermission() works in @PreAuthorize.
     * Used by @RequiresPermission annotation and any explicit hasPermission() calls.
     */
    @Bean
    public MethodSecurityExpressionHandler methodSecurityExpressionHandler() {
        DefaultMethodSecurityExpressionHandler handler =
                new DefaultMethodSecurityExpressionHandler();
        handler.setPermissionEvaluator(ecmPermissionEvaluator);
        return handler;
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
