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
 * In production, pipe these logs to your log aggregator (Datadog, ELK, Splunk).
 * The correlationId field links gateway logs to downstream service logs.
 */
@Slf4j
@Component
public class RequestLoggingFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request  = exchange.getRequest();
        long              startMs  = System.currentTimeMillis();

        String method        = request.getMethod().name();
        String path          = request.getPath().value();
        String correlationId = request.getHeaders()
                .getFirst(CorrelationIdFilter.CORRELATION_ID_HEADER);
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
                            long   durationMs = System.currentTimeMillis() - startMs;
                            int    status     = exchange.getResponse().getStatusCode() != null
                                    ? exchange.getResponse().getStatusCode().value()
                                    : 0;

                            // Structured log — easy to parse with a log aggregator
                            log.info("ACCESS method={} path={} status={} ms={} correlationId={} userId={} ip={}",
                                    method, path, status, durationMs, correlationId, userId, ip);
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
        // Run just after CorrelationIdFilter so correlationId is already set
        return Ordered.HIGHEST_PRECEDENCE + 2;
    }
}