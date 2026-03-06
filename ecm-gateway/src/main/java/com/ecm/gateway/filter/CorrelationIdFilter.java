package com.ecm.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Global filter — assigns a unique X-Correlation-ID to every request.
 *
 * Reuses the caller's ID if present (retry / distributed trace propagation),
 * otherwise generates a new UUID.
 *
 * The ID is forwarded to downstream services on the REQUEST and echoed back
 * on the RESPONSE so the frontend can surface it in error messages.
 *
 * ── WHY ServerHttpRequestDecorator instead of exchange.getRequest().mutate() ──
 *
 * Inside the Spring Security WebFlux filter chain the request is wrapped with
 * ReadOnlyHttpHeaders. Calling exchange.getRequest().mutate().header(...) uses
 * DefaultServerHttpRequestBuilder which stores "new HttpHeaders(request.getHeaders())".
 * When the underlying map is a ReadOnlyHttpHeaders, HttpHeaders.put() delegates
 * to ReadOnlyHttpHeaders.put() which throws UnsupportedOperationException.
 *
 * ServerHttpRequestDecorator overrides getHeaders() to build a completely fresh
 * HttpHeaders from scratch, copies all existing headers into it, then adds ours.
 * This never touches the original read-only map so no exception is possible.
 *
 * ── WHY beforeCommit() for the response header ──────────────────────────────
 *
 * Response headers are frozen as ReadOnlyHttpHeaders once the first byte of the
 * response body is written to the wire. beforeCommit() fires just before the
 * status line + headers are flushed — they are still mutable at that exact point.
 * A direct exchange.getResponse().getHeaders().add(...) outside beforeCommit()
 * races against the flush and throws the same UnsupportedOperationException.
 */
@Slf4j
@Component
public class CorrelationIdFilter implements GlobalFilter, Ordered {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-ID";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {

        // ── 1. Resolve the correlation ID ─────────────────────────────────────
        String existing = exchange.getRequest()
                .getHeaders()
                .getFirst(CORRELATION_ID_HEADER);

        final String correlationId = (existing != null && !existing.isBlank())
                ? existing
                : UUID.randomUUID().toString();

        // ── 2. Inject into the outbound REQUEST via a decorator ───────────────
        // Builds a brand-new HttpHeaders from scratch so we never touch the
        // read-only map that Spring Security wraps around the original request.
        ServerHttpRequestDecorator decoratedRequest =
                new ServerHttpRequestDecorator(exchange.getRequest()) {
                    @Override
                    public HttpHeaders getHeaders() {
                        HttpHeaders mutable = new HttpHeaders();
                        mutable.putAll(super.getHeaders());          // copy existing
                        mutable.set(CORRELATION_ID_HEADER, correlationId); // add ours
                        return mutable;
                    }
                };

        // ── 3. Echo onto the RESPONSE via beforeCommit ────────────────────────
        // beforeCommit fires just before Spring flushes headers to the network,
        // while they are still mutable. Safe for all response types.
        exchange.getResponse().beforeCommit(() -> {
            exchange.getResponse()
                    .getHeaders()
                    .set(CORRELATION_ID_HEADER, correlationId);
            return Mono.empty();
        });

        log.debug("Request [{} {}] correlationId={}",
                exchange.getRequest().getMethod(),
                exchange.getRequest().getPath(),
                correlationId);

        return chain.filter(
                exchange.mutate().request(decoratedRequest).build()
        );
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}