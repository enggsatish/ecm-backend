package com.ecm.workflow.config;

import com.ecm.common.security.EcmJwtConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

import java.util.Set;

/**
 * Security configuration for ecm-workflow.
 *
 * <p>Extends the base SecurityConfig from ecm-common by adding internal
 * service-to-service access for specific endpoints used by ecm-admin.</p>
 *
 * <p>Internal endpoints:</p>
 * <ul>
 *   <li>GET  /api/workflow/admin/definitions — ecm-admin fetches workflow list for product config</li>
 *   <li>POST /api/workflow/instances/internal-start — ecm-admin triggers workflow on document upload</li>
 * </ul>
 */
@Configuration
@EnableMethodSecurity
public class WorkflowSecurityConfig {

    private static final String INTERNAL_HEADER = "X-Internal-Service";
    private static final Set<String> ALLOWED_SERVICES = Set.of("ecm-admin", "ecm-document", "ecm-internal");

    @Bean("workflowSecurityFilterChain")
    @Order(1)
    public SecurityFilterChain workflowSecurityFilterChain(
            HttpSecurity http,
            EcmJwtConverter ecmJwtConverter,
            JwtDecoder jwtDecoder
    ) throws Exception {

        http
                .securityMatcher("/api/workflow/**", "/actuator/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(cors -> cors.disable())

                .authorizeHttpRequests(auth -> auth
                        // Health/metrics — always open
                        .requestMatchers("/actuator/health", "/actuator/info", "/actuator/prometheus").permitAll()

                        // Internal service-to-service — X-Internal-Service header whitelisted
                        .requestMatchers(request -> {
                            String header = request.getHeader(INTERNAL_HEADER);
                            if (header == null || !ALLOWED_SERVICES.contains(header)) return false;
                            String path = request.getServletPath();
                            return path.contains("/internal-start")
                                    || path.contains("/admin/definitions");
                        }).permitAll()

                        // Everything else requires JWT
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
