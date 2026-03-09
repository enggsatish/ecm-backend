package com.ecm.document.config;

import com.ecm.common.security.EcmJwtConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Replaces ecm-common's SecurityConfig as the active security chain for ecm-document.
 *
 * ── Why the previous two-chain approach (securityMatcher) failed ──────────────
 *
 * The v1 fix created a second SecurityFilterChain with a securityMatcher that
 * tried to intercept only the internal upload calls. This approach is unreliable
 * because:
 *
 *   1. ecm-common's SecurityConfig carries no @Order annotation, which means Spring
 *      Security assigns it an unspecified default order. Depending on bean registration
 *      order, it could evaluate before the custom chain even though @Order(1) was set.
 *
 *   2. @Order on a @Configuration class does NOT control SecurityFilterChain ordering.
 *      The @Order must be on the @Bean method that returns the SecurityFilterChain.
 *
 *   3. When two chains both have no securityMatcher, Spring Security 6 routes each
 *      request to the FIRST matching chain only. If ecm-common's chain fires first,
 *      the internal upload bypass chain never runs.
 *
 * ── How this fix works ────────────────────────────────────────────────────────
 *
 * This class defines a SINGLE SecurityFilterChain with @Order(1) on the @Bean method.
 * It has NO securityMatcher, so it matches ALL incoming requests to ecm-document.
 * Because it has the lowest order number, it always wins over ecm-common's chain
 * (default order ≈ Integer.MAX_VALUE - 5).
 *
 * ecm-common's filterChain() bean is still registered (it's a @Bean in a scanned
 * @Configuration), but it never runs because this chain already handles every request.
 *
 * Inside this chain, a RequestMatcher in authorizeHttpRequests permits internal
 * service calls without JWT; all other requests require a valid Okta JWT as before.
 *
 * ── Bean injection ────────────────────────────────────────────────────────────
 *
 * EcmJwtConverter  — @Component in ecm-common; injected by Spring.
 * JwtDecoder       — @Bean in ecm-common's SecurityConfig (bean name "jwtDecoder");
 *                    injected by type. We do NOT redefine it here to avoid duplicate
 *                    bean conflicts.
 *
 * ── @EnableWebSecurity NOT present ───────────────────────────────────────────
 *
 * @EnableWebSecurity is already on ecm-common's SecurityConfig, which is picked up
 * via component scan (scanBasePackages includes "com.ecm.common"). Adding it again
 * here causes duplicate registration warnings and is unnecessary.
 */
@Configuration
public class DocumentSecurityConfig {

    /** Must match the header sent by DocumentPromotionClient in ecm-common. */
    private static final String INTERNAL_HEADER = "X-Internal-Service";
    private static final String INTERNAL_VALUE  = "ecm-eforms";

    /**
     * Single security filter chain for all ecm-document requests.
     *
     * @Order(1) on the @Bean method — this is the ONLY place the order needs
     * to be declared for SecurityFilterChain priority. @Order on the class
     * does not affect bean-level ordering.
     *
     * @param http            per-bean HttpSecurity (injected by Spring Security)
     * @param ecmJwtConverter authority converter from ecm-common
     * @param jwtDecoder      audience-validating decoder from ecm-common's SecurityConfig
     */
    @Bean("documentSecurityFilterChain")
    @Order(1)
    public SecurityFilterChain documentSecurityFilterChain(
            HttpSecurity http,
            EcmJwtConverter ecmJwtConverter,
            JwtDecoder jwtDecoder
    ) throws Exception {

        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(cors -> cors.disable())

                .authorizeHttpRequests(auth -> auth

                        // ── Always open ──────────────────────────────────────────────
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()

                        // ── Internal service-to-service bypass ───────────────────────
                        // Permits DocumentPromotionClient calls from ecm-eforms.
                        // Conditions: POST + /api/documents/upload + correct header.
                        // All three must be true — a stray POST to /upload without the
                        // header still requires a valid JWT via .anyRequest().authenticated().
                        .requestMatchers(request ->
                                "POST".equalsIgnoreCase(request.getMethod())
                                        && "/api/documents/upload".equals(request.getServletPath())
                                        && INTERNAL_VALUE.equals(request.getHeader(INTERNAL_HEADER))
                        ).permitAll()

                        // ── Everything else requires a valid Okta JWT ────────────────
                        .anyRequest().authenticated()
                )

                // ── JWT validation for authenticated requests ─────────────────────
                // oauth2ResourceServer only runs for requests that reach .authenticated()
                // above. Requests already permitted by .permitAll() skip JWT validation.
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt
                                .decoder(jwtDecoder)
                                .jwtAuthenticationConverter(ecmJwtConverter)
                        )
                );

        return http.build();
    }
}