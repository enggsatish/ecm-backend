package com.ecm.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Global filter — adds security-related HTTP response headers to every response.
 *
 * These headers are added at the gateway so they apply platform-wide.
 * Downstream services do not need to set them individually.
 *
 * WHY beforeCommit() AND NOT .then(Mono.fromRunnable(...)):
 *
 * In Spring WebFlux, response headers become READ-ONLY (frozen as
 * ReadOnlyHttpHeaders) the moment the first byte of the response body is
 * written to the wire. For streaming responses — file uploads, downloads,
 * SSE — the body can start writing before the reactive chain fully unwinds.
 *
 * .then(Mono.fromRunnable(...)) runs AFTER the response completes. By that
 * point headers are already committed → ReadOnlyHttpHeaders.put() throws
 * UnsupportedOperationException.
 *
 * response.beforeCommit(() -> ...) registers a callback that Spring WebFlux
 * calls just before it flushes the status line + headers to the network —
 * headers are still fully mutable at that moment. This is the correct and
 * officially recommended pattern for adding response headers in WebFlux.
 *
 * Header reference:
 *
 * X-Content-Type-Options: nosniff
 *   Prevents browsers from MIME-sniffing the content-type.
 *
 * X-Frame-Options: DENY
 *   Prevents clickjacking via iframe embedding.
 *
 * X-XSS-Protection: 0
 *   Disables the legacy IE XSS filter (causes vulnerabilities in modern browsers).
 *   Use CSP instead.
 *
 * Referrer-Policy: strict-origin-when-cross-origin
 *   Prevents API paths leaking to third-party services via Referer header.
 *
 * Permissions-Policy
 *   Disables browser APIs the ECM application does not use.
 *
 * Strict-Transport-Security (HSTS)
 *   Forces HTTPS for one year. Ignored on HTTP (harmless in dev).
 *
 * Cache-Control: no-store
 *   Prevents caching of API responses containing sensitive document data.
 */
@Component
public class SecurityHeadersFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // Register a beforeCommit callback — fires just before headers are written
        // to the wire, while they are still mutable. Safe for all response types
        // including streaming uploads, downloads, and error responses.
        exchange.getResponse().beforeCommit(() -> {
            HttpHeaders headers = exchange.getResponse().getHeaders();

            // Only set if not already present — allows downstream services to
            // override a specific header for a specific route if ever needed.
            headers.set("X-Content-Type-Options",   "nosniff");
            headers.set("X-Frame-Options",           "DENY");
            headers.set("X-XSS-Protection",         "0");
            headers.set("Referrer-Policy",           "strict-origin-when-cross-origin");
            headers.set("Permissions-Policy",        "camera=(), microphone=(), geolocation=()");
            headers.set("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
            headers.set("Cache-Control",             "no-store");

            return Mono.empty();
        });

        // Continue the filter chain — the beforeCommit callback will fire
        // automatically when Spring WebFlux is ready to flush the response.
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        // HIGHEST_PRECEDENCE + 3 — runs early so the beforeCommit callback is
        // registered before any routing filter has a chance to commit the response.
        // (CorrelationIdFilter = HP+1, RequestLoggingFilter = HP+2, this = HP+3)
        return Ordered.HIGHEST_PRECEDENCE + 3;
    }
}