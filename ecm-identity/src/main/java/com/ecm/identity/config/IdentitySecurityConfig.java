package com.ecm.identity.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Identity-specific security override for internal service-to-service endpoints.
 *
 * WHY A SEPARATE SecurityFilterChain:
 * The shared ecm-common SecurityConfig requires JWT on all requests.
 * The /internal/auth/enrich endpoint is called by ecm-gateway — which has
 * already validated the JWT — so re-validating here is wrong.
 * In production, access is further restricted by network routing (gateway
 * does not expose /internal/** to external clients).
 *
 * @Order(1) — this filter chain runs BEFORE the common SecurityConfig
 * (which has default order). It matches /internal/** only and permits
 * without authentication. All other requests fall through to common config.
 */
@Configuration
public class IdentitySecurityConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain internalFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/internal/**")
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session
                    .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                    .anyRequest().permitAll()
            );

        return http.build();
    }
}
