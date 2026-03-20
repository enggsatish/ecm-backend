package com.ecm.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Global filter — writes one structured log line per request.
 *
 * Format:
 *   ACCESS method=GET path=/api/documents status=200 ms=45
 *          correlationId=uuid userId=okta-sub ip=1.2.3.4
 *
 * Deliberately does NOT log:
 *   - Authorization header (would expose tokens in log files — security risk)
 *   - Request/response bodies (too large, may contain PII)
 *   - Query parameters (may contain sensitive search terms)
 *
 * Order: HIGHEST_PRECEDENCE + 2
 *   CorrelationIdFilter (HP+1) runs first and sets the X-Request-ID on the
 *   mutated request, so by the time this filter runs the header is present.
 *
 * ── SPRINT I OTEL NOTE ───────────────────────────────────────────────────────
 * With micrometer-tracing-bridge-otel + context-propagation on the classpath,
 * Spring Boot's ObservationWebFilter (order -100) already populated the Reactor
 * context with the OTEL traceId before this filter runs. We deliberately do NOT
 * read or log the traceId here — Jaeger correlates spans to logs by timestamp.
 * The correlationId field (X-Request-ID) is a separate business-level ID.
 * ─────────────────────────────────────────────────────────────────────────────
 */
@Slf4j                 // ← Lombok generates the 'log' field. Do NOT add an explicit Logger here.
@Component
public class RequestLoggingFilter implements GlobalFilter, Ordered {

    // Local constant — do NOT reference CorrelationIdFilter.CORRELATION_ID_HEADER
    // because that field is private. Both filters use the same header string.
    private static final String CORRELATION_ID_HEADER = "X-Request-ID";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        long              startMs = System.currentTimeMillis();

        String method        = request.getMethod().name();
        String path          = request.getPath().value();
        // CorrelationIdFilter (order HP+1) has already set this header on the
        // mutated request before this filter (HP+2) runs.
        String correlationId = request.getHeaders().getFirst(CORRELATION_ID_HEADER);
        String ip            = extractClientIp(request);

        return ReactiveSecurityContextHolder.getContext()
                // Extract userId from JWT — available after authentication
                .map(ctx -> {
                    var auth = ctx.getAuthentication();
                    if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
                        return jwt.getSubject();
                    }
                    return "anonymous";
                })
                .defaultIfEmpty("anonymous")
                .flatMap(userId ->
                        chain.filter(exchange).then(Mono.fromRunnable(() -> {
                            long durationMs = System.currentTimeMillis() - startMs;
                            int  status     = exchange.getResponse().getStatusCode() != null
                                    ? exchange.getResponse().getStatusCode().value()
                                    : 0;

                            if (path.startsWith("/actuator")) {
                                // Keep actuator noise at DEBUG so it doesn't flood INFO logs
                                log.debug("ACCESS method={} path={} status={} ms={} correlationId={} userId={} ip={}",
                                        method, path, status, durationMs, correlationId, userId, ip);
                            } else {
                                // Structured log — easy to parse with Jaeger / ELK / Datadog
                                log.info("ACCESS method={} path={} status={} ms={} correlationId={} userId={} ip={}",
                                        method, path, status, durationMs, correlationId, userId, ip);
                            }
                        }))
                );
    }

    private String extractClientIp(ServerHttpRequest request) {
        // Respect X-Forwarded-For from load balancer / reverse proxy
        String forwarded = request.getHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        if (request.getRemoteAddress() != null) {
            return request.getRemoteAddress().getAddress().getHostAddress();
        }
        return "unknown";
    }

    @Override
    public int getOrder() {
        // Run just after CorrelationIdFilter (HP+1) so the X-Request-ID header
        // is guaranteed to be present on the request when we read it above.
        return Ordered.HIGHEST_PRECEDENCE + 2;
    }
}