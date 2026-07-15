package com.ecm.batch.config;

import com.ecm.common.security.EcmJwtConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Batch service security. Uses ecm-common's SecurityConfig for:
 *   - JwtDecoder (audience validation)
 *   - MethodSecurityExpressionHandler (EcmPermissionEvaluator for hasPermission())
 * Only overrides the SecurityFilterChain to add internal-header bypass.
 */
@Configuration
@EnableMethodSecurity
public class BatchSecurityConfig {

    private static final String INTERNAL_HEADER = "X-Internal-Service";
    private static final java.util.Set<String> ALLOWED_SERVICES =
            java.util.Set.of("ecm-admin", "ecm-document", "ecm-ocr", "ecm-internal");

    // methodSecurityExpressionHandler is defined in ecm-common SecurityConfig
    // — no need to redefine here. EcmPermissionEvaluator is auto-scanned from ecm-common.

    @Bean("batchSecurityFilterChain")
    @Order(1)
    public SecurityFilterChain batchSecurityFilterChain(
            HttpSecurity http,
            EcmJwtConverter ecmJwtConverter,
            JwtDecoder jwtDecoder
    ) throws Exception {

        http
                .securityMatcher("/api/batch/**", "/internal/**", "/actuator/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(cors -> cors.disable())

                .authorizeHttpRequests(auth -> auth
                        // Always open
                        .requestMatchers("/actuator/health", "/actuator/info", "/actuator/prometheus").permitAll()

                        // Internal service-to-service bypass (whitelist, null-safe)
                        .requestMatchers(request -> {
                            String header = request.getHeader(INTERNAL_HEADER);
                            return header != null && ALLOWED_SERVICES.contains(header);
                        }).permitAll()

                        // Everything else requires a valid JWT
                        .anyRequest().authenticated()
                )

                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt
                                .decoder(jwtDecoder)
                                .jwtAuthenticationConverter(ecmJwtConverter)
                        )
                );

        return http.build();
    }
}
