package com.ecm.admin.config;

import com.ecm.common.security.EcmJwtConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Replaces ecm-common's SecurityConfig as the active security chain for ecm-admin.
 *
 * Follows the same pattern as DocumentSecurityConfig in ecm-document:
 * single @Bean with @Order(1) on the method, no securityMatcher (catches all requests),
 * beats ecm-common's chain which has default order.
 *
 * Internal service-to-service calls (X-Internal-Service header) are permitted
 * for read-only endpoints used by ecm-ocr and ecm-batch during classification.
 *
 * @see com.ecm.common.client.AdminServiceClient — sends X-Internal-Service: ecm-internal
 */
@Configuration
public class AdminSecurityConfig {

    /** Must match the header sent by AdminServiceClient in ecm-common. */
    private static final String INTERNAL_HEADER = "X-Internal-Service";
    private static final java.util.Set<String> INTERNAL_VALUES = java.util.Set.of("ecm-internal", "ai-gateway");

    /**
     * Single security filter chain for all ecm-admin requests.
     *
     * @param http            per-bean HttpSecurity (injected by Spring Security)
     * @param ecmJwtConverter authority converter from ecm-common
     * @param jwtDecoder      audience-validating decoder from ecm-common's SecurityConfig
     */
    @Bean("adminSecurityFilterChain")
    @Order(1)
    public SecurityFilterChain adminSecurityFilterChain(
            HttpSecurity http,
            EcmJwtConverter ecmJwtConverter,
            JwtDecoder jwtDecoder
    ) throws Exception {

        http
                // Scope this chain to admin + internal paths; common SecurityConfig handles the rest
                .securityMatcher("/api/admin/**", "/internal/**", "/mcp/**", "/actuator/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(cors -> cors.disable())

                .authorizeHttpRequests(auth -> auth

                        // ── Always open ──────────────────────────────────────────────
                        .requestMatchers("/actuator/health", "/actuator/info", "/actuator/prometheus").permitAll()

                        // ── External participant access (OTP-based, no Okta) ─────────
                        .requestMatchers("/api/admin/cases/external/**").permitAll()

                        // ── Internal service-to-service bypass ───────────────────────
                        // Permits ecm-ocr and ecm-batch to call read-only internal
                        // endpoints via InternalAdminController (/internal/admin/*).
                        .requestMatchers(request -> {
                            String header = request.getHeader(INTERNAL_HEADER);
                            if (header == null || !INTERNAL_VALUES.contains(header)) return false;
                            String path = request.getServletPath();
                            // Internal admin endpoints (GET only)
                            if ("GET".equalsIgnoreCase(request.getMethod()) && path.startsWith("/internal/")) return true;
                            // MCP server endpoints (GET list + POST execute) — called by AI Gateway
                            if (path.startsWith("/mcp/")) return true;
                            return false;
                        }).permitAll()

                        // ── Everything else requires a valid Okta JWT ────────────────
                        .anyRequest().authenticated()
                )

                // ── JWT validation for authenticated requests ─────────────────────
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt
                                .decoder(jwtDecoder)
                                .jwtAuthenticationConverter(ecmJwtConverter)
                        )
                );

        return http.build();
    }
}
