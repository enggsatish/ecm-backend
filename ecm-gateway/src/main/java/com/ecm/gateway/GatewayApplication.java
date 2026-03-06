package com.ecm.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ECM API Gateway — port 8080.
 *
 * Single entry point for all frontend traffic in production.
 * In development, the Vite proxy handles routing directly to services.
 *
 * Responsibilities:
 *   - JWT validation (Okta public keys, audience check)
 *   - Request routing to downstream services
 *   - Rate limiting per user via Redis sliding window
 *   - Circuit breaker per downstream service (Resilience4j)
 *   - Correlation ID injection (X-Correlation-ID header)
 *   - Security response headers (CSP, HSTS, X-Frame-Options etc.)
 *   - Structured access logging
 *   - CORS (single policy for the whole platform)
 *
 * NOTE: Does NOT depend on ecm-common — that library uses Spring MVC/JPA
 * which are incompatible with this module's WebFlux (reactive) runtime.
 */
@SpringBootApplication
public class GatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}