package com.ecm.gateway.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/**
 * Route definitions for the ECM API Gateway.
 *
 * Route priority: Spring Cloud Gateway evaluates routes in declaration order.
 * More specific routes (e.g. /api/documents/upload) must appear before
 * less specific ones (/api/documents/**).
 *
 * Circuit breaker fallback: when a downstream service is unavailable,
 * the gateway forwards to FallbackController which returns a clean 503 JSON.
 *
 * Rate limiters are injected as Spring-managed beans from RateLimiterConfig
 * (NOT created inline with new RedisRateLimiter -- that bypasses Spring's
 * dependency injection and leaves ReactiveRedisTemplate null).
 *
 * In production all downstream services run on an internal network only --
 * their ports are NOT exposed externally. Only port 8080 (gateway) is public.
 */
@Configuration
public class RouteConfig {

    @Value("${ecm.services.identity-url:http://localhost:8081}")
    private String identityUrl;

    @Value("${ecm.services.document-url:http://localhost:8082}")
    private String documentUrl;

    @Value("${ecm.services.workflow-url:http://localhost:8083}")
    private String workflowUrl;

    @Value("${ecm.services.eforms-url:http://localhost:8084}")
    private String formflowUrl;

    @Value("${ecm.services.admin-url:http://localhost:8086}")
    private String adminUrl;

    @Value("${ecm.services.ocr-url:http://localhost:8087}")
    private String ocrUrl;

    // Injected from RateLimiterConfig -- Spring-managed beans with
    // ReactiveStringRedisTemplate properly wired in.
    private final RedisRateLimiter defaultRateLimiter;
    private final RedisRateLimiter uploadRateLimiter;

    public RouteConfig(
            @Qualifier("defaultRateLimiter") RedisRateLimiter defaultRateLimiter,
            @Qualifier("uploadRateLimiter")  RedisRateLimiter uploadRateLimiter) {
        this.defaultRateLimiter = defaultRateLimiter;
        this.uploadRateLimiter  = uploadRateLimiter;
    }

    @Bean
    public RouteLocator ecmRoutes(RouteLocatorBuilder builder) {
        return builder.routes()

                // -- ecm-identity: auth endpoints ---------------------------------
                // No rate limiting on auth -- login failures are self-limiting via Okta
                .route("identity-auth", r -> r
                        .path("/api/auth/**")
                        .filters(f -> f
                                .circuitBreaker(cb -> cb
                                        .setName("identity-cb")
                                        .setFallbackUri("forward:/fallback/identity"))
                                // Strip any X-Forwarded-Prefix added by reverse proxies
                                .removeRequestHeader("X-Forwarded-Prefix")
                        )
                        .uri(identityUrl)
                )

                // -- ecm-identity: user endpoints ---------------------------------
                .route("identity-users", r -> r
                        .path("/api/users/**")
                        .filters(f -> f
                                .requestRateLimiter(rl -> rl
                                        .setRateLimiter(defaultRateLimiter)
                                        .setStatusCode(HttpStatus.TOO_MANY_REQUESTS))
                                .circuitBreaker(cb -> cb
                                        .setName("identity-cb")
                                        .setFallbackUri("forward:/fallback/identity"))
                        )
                        .uri(identityUrl)
                )

                // -- ecm-document: upload -- stricter rate limit -------------------
                // Upload before the general /api/documents/** route (more specific first)
                .route("document-upload", r -> r
                        .path("/api/documents/upload")
                        .and().method(HttpMethod.POST)
                        .filters(f -> f
                                .requestRateLimiter(rl -> rl
                                        .setRateLimiter(uploadRateLimiter)
                                        .setStatusCode(HttpStatus.TOO_MANY_REQUESTS))
                                .circuitBreaker(cb -> cb
                                        .setName("document-cb")
                                        .setFallbackUri("forward:/fallback/document"))
                                // Tag responses so logs can identify the upload route
                                .setResponseHeader("X-Route", "document-upload")
                        )
                        .uri(documentUrl)
                )

                // -- ecm-document: all other document endpoints -------------------
                .route("document-service", r -> r
                        .path("/api/documents/**")
                        .filters(f -> f
                                .requestRateLimiter(rl -> rl
                                        .setRateLimiter(defaultRateLimiter)
                                        .setStatusCode(HttpStatus.TOO_MANY_REQUESTS))
                                .circuitBreaker(cb -> cb
                                        .setName("document-cb")
                                        .setFallbackUri("forward:/fallback/document"))
                        )
                        .uri(documentUrl)
                )

                // -- ecm-workflow: workflow, task, and definition endpoints --------
                .route("workflow-service", r -> r
                        .path("/api/workflow/**")
                        .filters(f -> f
                                .requestRateLimiter(rl -> rl
                                        .setRateLimiter(defaultRateLimiter)
                                        .setStatusCode(HttpStatus.TOO_MANY_REQUESTS))
                                .circuitBreaker(cb -> cb
                                        .setName("workflow-cb")
                                        .setFallbackUri("forward:/fallback/workflow"))
                        )
                        .uri(workflowUrl)
                )
                // Efrom fall back route.
                 .route("eforms-service", r -> r
                     .path("/api/eforms/**")
                     .filters(f -> f
                         .requestRateLimiter(rl -> rl
                             .setRateLimiter(defaultRateLimiter)
                             .setStatusCode(HttpStatus.TOO_MANY_REQUESTS))
                         .circuitBreaker(cb -> cb
                             .setName("eforms-cb")
                             .setFallbackUri("forward:/fallback/eforms")))
                     .uri(formflowUrl)
                 )
                // Admin service fall back route.
                .route("admin-service", r -> r
                        .path("/api/admin/**")
                        .filters(f -> f
                                .requestRateLimiter(rl -> rl
                                        .setRateLimiter(defaultRateLimiter)
                                        .setStatusCode(HttpStatus.TOO_MANY_REQUESTS))
                                .circuitBreaker(cb -> cb
                                        .setName("admin-cb")
                                        .setFallbackUri("forward:/fallback/admin")))
                        .uri(adminUrl)
                )
                // Oct service fallback route.
                .route("ocr-service", r -> r
                        .path("/api/ocr/**")
                        .filters(f -> f
                                .requestRateLimiter(rl -> rl
                                        .setRateLimiter(defaultRateLimiter)
                                        .setStatusCode(HttpStatus.TOO_MANY_REQUESTS))
                                .circuitBreaker(cb -> cb
                                        .setName("ocr-cb")
                                        .setFallbackUri("forward:/fallback/ocr")))
                        .uri(ocrUrl)
                )
                .build();
    }
}