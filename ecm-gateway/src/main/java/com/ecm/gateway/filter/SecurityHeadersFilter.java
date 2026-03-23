package com.ecm.gateway.filter;

import org.springframework.beans.factory.annotation.Value;
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

    @Value("${OKTA_ISSUER_URI:https://integrator-3023444.okta.com/oauth2/ausykohz3k9z4e9Wy697}")
    private String oktaIssuerUri;

    /**
     * Extracts the origin (scheme + host) from the Okta issuer URI.
     * e.g. "https://integrator-3023444.okta.com/oauth2/xxx" → "https://integrator-3023444.okta.com"
     */
    private String getOktaOrigin() {
        try {
            java.net.URI uri = java.net.URI.create(oktaIssuerUri);
            return uri.getScheme() + "://" + uri.getHost();
        } catch (Exception e) {
            return "https://*.okta.com";
        }
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        exchange.getResponse().beforeCommit(() -> {
            HttpHeaders headers = exchange.getResponse().getHeaders();

            String oktaOrigin = getOktaOrigin();

            headers.set("X-Content-Type-Options",   "nosniff");
            // X-Frame-Options: allow Okta iframe for silent token renewal,
            // block all others. CSP frame-ancestors provides the real protection.
            headers.set("X-Frame-Options",           "SAMEORIGIN");
            headers.set("X-XSS-Protection",         "0");
            headers.set("Referrer-Policy",           "strict-origin-when-cross-origin");
            headers.set("Permissions-Policy",        "camera=(), microphone=(), geolocation=()");
            headers.set("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
            headers.set("Cache-Control",             "no-store");

            // ── Content Security Policy ──────────────────────────────────
            // Protects against XSS by whitelisting allowed content sources.
            //
            // Key decisions:
            //   script-src 'self' — blocks inline scripts and external script injection
            //   style-src 'self' 'unsafe-inline' — Tailwind CSS uses inline styles
            //   connect-src 'self' + Okta — API calls + token renewal endpoint
            //   frame-src + Okta — hidden iframe for silent token renewal
            //   img-src 'self' data: blob: — inline SVGs, base64 images, MinIO blobs
            //   frame-ancestors 'self' — prevents clickjacking (replaces X-Frame-Options)
            String csp = String.join("; ",
                    "default-src 'self'",
                    "script-src 'self'",
                    "style-src 'self' 'unsafe-inline'",
                    "img-src 'self' data: blob:",
                    "font-src 'self' data:",
                    "connect-src 'self' " + oktaOrigin,
                    "frame-src 'self' " + oktaOrigin,
                    "frame-ancestors 'self'",
                    "base-uri 'self'",
                    "form-action 'self' " + oktaOrigin,
                    "object-src 'none'"
            );
            headers.set("Content-Security-Policy", csp);

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