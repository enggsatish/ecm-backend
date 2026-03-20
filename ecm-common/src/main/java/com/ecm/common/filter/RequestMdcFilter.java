package com.ecm.common.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * RequestMdcFilter — populates SLF4J MDC for every inbound HTTP request.
 *
 * ══════════════════════════════════════════════════════════════════════════════
 * SPRINT I OTEL CHANGE — READ CAREFULLY
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * Before Sprint I, this filter generated its own UUID traceId and wrote it
 * to MDC with MDC.put("traceId", UUID.randomUUID()...).
 *
 * That MUST NOT happen after OTEL is enabled.
 *
 * WHY: Spring Boot's ObservationWebFilter (order -100) runs BEFORE this filter
 * (order 1) and has already populated MDC with the real OTEL traceId and spanId,
 * derived from the incoming W3C traceparent header (or generated fresh if no
 * header present). If we overwrite MDC("traceId") with a random UUID, logs from
 * this service will never correlate with spans in Jaeger.
 *
 * WHAT THIS FILTER NOW DOES:
 *   - Reads X-Request-ID header as a "correlationId" (separate from traceId)
 *   - Adds requestUri, requestMethod, clientIp to MDC for structured logging
 *   - Does NOT touch traceId or spanId — OTEL owns those
 *
 * MDC keys available in logback after this filter runs:
 *   traceId       — set by OTEL/Micrometer (from W3C traceparent or generated)
 *   spanId        — set by OTEL/Micrometer
 *   correlationId — X-Request-ID header (or generated UUID if absent)
 *   requestUri    — e.g. /api/documents
 *   requestMethod — GET, POST, etc.
 *   clientIp      — X-Forwarded-For or remote address
 * ══════════════════════════════════════════════════════════════════════════════
 */
@Component
@Order(1)  // Runs after ObservationWebFilter (order -100) — do not change
public class RequestMdcFilter extends OncePerRequestFilter {

    private static final String CORRELATION_ID_HEADER = "X-Request-ID";
    private static final String MDC_CORRELATION_ID    = "correlationId";
    private static final String MDC_REQUEST_URI       = "requestUri";
    private static final String MDC_REQUEST_METHOD    = "requestMethod";
    private static final String MDC_CLIENT_IP         = "clientIp";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        try {
            // ── Correlation ID (X-Request-ID) ────────────────────────────────
            // This is a business-level correlation ID from the calling client,
            // completely separate from the OTEL traceId.
            // Gateway injects X-Request-ID from the W3C traceparent or generates one.
            String correlationId = request.getHeader(CORRELATION_ID_HEADER);
            if (correlationId == null || correlationId.isBlank()) {
                correlationId = UUID.randomUUID().toString();
            }

            // ── Populate MDC ─────────────────────────────────────────────────
            // NOTE: traceId and spanId are already in MDC via ObservationWebFilter.
            //       We only add our own fields here.
            MDC.put(MDC_CORRELATION_ID, correlationId);
            MDC.put(MDC_REQUEST_URI, request.getRequestURI());
            MDC.put(MDC_REQUEST_METHOD, request.getMethod());
            MDC.put(MDC_CLIENT_IP, resolveClientIp(request));

            // ── Pass correlationId downstream ─────────────────────────────────
            // Useful for response header so clients can correlate their request
            response.setHeader(CORRELATION_ID_HEADER, correlationId);

            filterChain.doFilter(request, response);

        } finally {
            // ── Clean up OUR MDC keys only ───────────────────────────────────
            // Do NOT clear traceId or spanId — Micrometer Tracing manages those.
            MDC.remove(MDC_CORRELATION_ID);
            MDC.remove(MDC_REQUEST_URI);
            MDC.remove(MDC_REQUEST_METHOD);
            MDC.remove(MDC_CLIENT_IP);
        }
    }

    /**
     * Resolves the real client IP, respecting X-Forwarded-For from the gateway.
     */
    private String resolveClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            // X-Forwarded-For can be a comma-separated list; first entry is client
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}