package com.ecm.common.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Servlet filter that populates SLF4J MDC (Mapped Diagnostic Context) with
 * per-request correlation fields before any other processing occurs.
 *
 * Every log.info/warn/error call on the same thread automatically includes
 * these fields when the Logback pattern references %X{traceId} etc.
 * The logback-spring.xml configuration picks them up without any changes to
 * individual log statements.
 *
 * Fields set:
 *   traceId   — X-Request-ID header from gateway, or a generated 16-char hex ID
 *   method    — HTTP verb
 *   path      — request URI
 *   userId    — JWT subject (Entra object ID) when authenticated
 *   userEmail — JWT email claim when present
 *
 * IMPORTANT: MDC.clear() in the finally block is mandatory. HTTP threads are
 * reused from a pool; without clearing, the next request on the same thread
 * inherits the previous request's MDC values.
 *
 * Place this class in ecm-common. It is picked up automatically via
 * Spring Boot's component scan in every module that includes ecm-common.
 */
@Slf4j
@Component
@Order(1)  // run before security filters so MDC is populated during auth processing
public class RequestMdcFilter extends OncePerRequestFilter {

    private static final String HEADER_REQUEST_ID = "X-Request-ID";
    private static final String HEADER_TRACE_ID   = "X-Trace-ID";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            // ── 1. Trace ID ───────────────────────────────────────────────────
            // Prefer the gateway-injected X-Request-ID so the same ID spans all
            // services. Fall back to a locally-generated one.
            String traceId = request.getHeader(HEADER_REQUEST_ID);
            if (traceId == null || traceId.isBlank()) {
                traceId = UUID.randomUUID().toString()
                        .replace("-", "").substring(0, 16);
            }
            MDC.put("traceId", traceId);

            // ── 2. HTTP context ───────────────────────────────────────────────
            MDC.put("method", request.getMethod());
            MDC.put("path",   request.getRequestURI());

            // ── 3. Identity from JWT ──────────────────────────────────────────
            // SecurityContextHolder may not be populated yet at filter-chain order 1.
            // This is a best-effort: if JWT is available, add it; otherwise the
            // AuditAspect and service layer can add MDC fields inside their own logic.
            try {
                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
                    MDC.put("userId", jwt.getSubject());
                    String email = jwt.getClaimAsString("email");
                    if (email != null && !email.isBlank()) {
                        MDC.put("userEmail", email);
                    }
                }
            } catch (Exception ignored) {
                // Non-fatal — identity enrichment is best-effort in filter
            }

            // ── 4. Echo trace ID in response ──────────────────────────────────
            // Allows the frontend / API consumer to correlate a request to its
            // backend log lines without digging through timestamps.
            response.setHeader(HEADER_TRACE_ID, traceId);

            chain.doFilter(request, response);

        } finally {
            // ── 5. Always clear MDC ───────────────────────────────────────────
            // Thread pool reuse means stale MDC values carry over to the next
            // request if we don't clear here.
            MDC.clear();
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Skip MDC overhead for actuator health probes — they generate a lot of noise
        String path = request.getRequestURI();
        return path.startsWith("/actuator/");
    }
}