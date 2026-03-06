package com.ecm.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtGrantedAuthoritiesConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * WebFlux Security configuration for ecm-gateway.
 *
 * WHY THIS CLASS IS NEEDED
 * ─────────────────────────
 * The gateway has spring.security.oauth2.resourceserver.jwt.issuer-uri
 * configured in application.yml. Spring Boot's auto-configuration
 * therefore creates a default SecurityWebFilterChain that requires
 * JWT authentication on EVERY request — including OPTIONS preflight.
 *
 * Browser CORS preflight (OPTIONS) never carries an Authorization header.
 * The auto-configured chain rejects it with 401 before the CORS filter
 * can add Access-Control-Allow-Origin, so the browser sees no CORS header
 * on a 401 response and blocks the actual request.
 *
 * This explicit SecurityWebFilterChain overrides the auto-configuration
 * and does two things:
 *   1. Permits OPTIONS requests without authentication.
 *   2. Wires CORS via the reactive CorsConfigurationSource bean defined
 *      here, which aligns with Spring Security's WebFlux filter ordering
 *      (Security runs before Gateway route filters; CORS must be handled
 *      at the Security layer, not just in globalcors yaml).
 *
 * CORS IS CONFIGURED IN TWO PLACES — both are required:
 *   • spring.cloud.gateway.globalcors (application.yml)
 *     → handles CORS for requests that reach the routing layer
 *   • corsConfigurationSource() bean below
 *     → handles CORS at the Spring Security layer (catches preflight
 *        before it ever reaches the routing layer)
 *   Without both, preflight OPTIONS requests are blocked by Security
 *   before Gateway's CORS filter gets to process them.
 */
@Configuration
@EnableWebFluxSecurity
public class GatewaySecurityConfig {

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String issuerUri;

    @Value("${okta.oauth2.audience}")
    private String audience;

    @Value("#{'${ecm.security.allowed-origins:http://localhost:3000,http://localhost:4200}'.split(',')}")
    private List<String> allowedOrigins;

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)

                // Wire CORS at the Security layer — must match globalcors in yml.
                // Security-layer CORS runs before routing, so preflight OPTIONS
                // responses get Access-Control-Allow-Origin even when the
                // downstream route is protected.
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))

                .authorizeExchange(exchanges -> exchanges
                        // ── Permit OPTIONS preflight for all paths ────────────────
                        // Preflight carries no JWT. Blocking it here would cause the
                        // browser to cancel every subsequent authenticated API call.
                        .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                        // ── Actuator health (used by Docker/k8s probes) ───────────
                        .pathMatchers("/actuator/health", "/actuator/info").permitAll()

                        // ── Circuit breaker fallback endpoints ────────────────────
                        .pathMatchers("/fallback/**").permitAll()

                        // ── Everything else requires a valid Okta JWT ─────────────
                        .anyExchange().authenticated()
                )

                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt
                                .jwtDecoder(reactiveJwtDecoder())
                                .jwtAuthenticationConverter(jwtAuthenticationConverter())
                        )
                );

        return http.build();
    }

    /**
     * Reactive JWT decoder — validates issuer and audience.
     * Gateway uses WebFlux (reactive), so NimbusReactiveJwtDecoder is required.
     * The blocking NimbusJwtDecoder used in ecm-common is MVC-only and
     * cannot be used here.
     */
    @Bean
    public ReactiveJwtDecoder reactiveJwtDecoder() {
        NimbusReactiveJwtDecoder decoder =
                NimbusReactiveJwtDecoder.withIssuerLocation(issuerUri).build();

        OAuth2TokenValidator<Jwt> issuerValidator =
                JwtValidators.createDefaultWithIssuer(issuerUri);

        OAuth2TokenValidator<Jwt> audienceValidator =
                token -> {
                    List<String> audiences = token.getAudience();
                    if (audiences != null && audiences.contains(audience)) {
                        return org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.success();
                    }
                    return org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.failure(
                            new org.springframework.security.oauth2.core.OAuth2Error(
                                    "invalid_audience",
                                    "JWT audience claim does not contain required audience: " + audience,
                                    null
                            )
                    );
                };

        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(issuerValidator, audienceValidator)
        );

        return decoder;
    }

    /**
     * Maps the Okta "groups" claim to Spring Security granted authorities
     * at the gateway level, so downstream services receive an
     * X-Forwarded-User header with role information if needed in future.
     */
    @Bean
    public ReactiveJwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authoritiesConverter =
                new JwtGrantedAuthoritiesConverter();
        authoritiesConverter.setAuthoritiesClaimName("groups");
        authoritiesConverter.setAuthorityPrefix("ROLE_");

        ReactiveJwtAuthenticationConverter converter =
                new ReactiveJwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(
                new ReactiveJwtGrantedAuthoritiesConverterAdapter(authoritiesConverter)
        );
        return converter;
    }

    /**
     * Reactive CORS configuration source — mirrors globalcors in application.yml.
     *
     * Spring Security's CORS filter runs BEFORE the Gateway routing layer.
     * This bean ensures OPTIONS preflight responses always include
     * Access-Control-Allow-Origin, even when the request never reaches
     * the downstream route.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(
                List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(
                List.of("Authorization", "Content-Type", "X-Requested-With", "X-Correlation-ID"));
        config.setExposedHeaders(
                List.of("X-Total-Count", "X-Correlation-ID"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}