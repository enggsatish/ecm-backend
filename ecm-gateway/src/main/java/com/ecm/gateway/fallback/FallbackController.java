package com.ecm.gateway.fallback;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Circuit breaker fallback controller.
 *
 * When a downstream service fails enough times to open its circuit breaker,
 * Spring Cloud Gateway forwards the request here instead of to the service.
 *
 * Returns a consistent ApiResponse-shaped JSON (matching ecm-common's ApiResponse)
 * so the frontend handles 503 the same way it handles any other API error.
 *
 * The circuit breaker is configured in application.yml under resilience4j.
 * It opens when 50% of the last 10 calls fail, and retries after 30 seconds.
 */
@Slf4j
@RestController
public class FallbackController {

    @RequestMapping("/fallback/identity")
    public ResponseEntity<Map<String, Object>> identityFallback(ServerWebExchange exchange) {
        logFallback("ecm-identity", exchange);
        return buildFallback("The authentication service is temporarily unavailable. " +
                "Please try again in a moment.", "IDENTITY_SERVICE_UNAVAILABLE");
    }

    @RequestMapping("/fallback/document")
    public ResponseEntity<Map<String, Object>> documentFallback(ServerWebExchange exchange) {
        logFallback("ecm-document", exchange);
        return buildFallback("The document service is temporarily unavailable. " +
                "Your files are safe. Please try again in a moment.", "DOCUMENT_SERVICE_UNAVAILABLE");
    }

    @RequestMapping("/fallback/workflow")
    public ResponseEntity<Map<String, Object>> workflowFallback(ServerWebExchange exchange) {
        logFallback("ecm-workflow", exchange);
        return buildFallback("The workflow service is temporarily unavailable.", "WORKFLOW_SERVICE_UNAVAILABLE");
    }

    @RequestMapping("/fallback/eforms")
    public ResponseEntity<Map<String, Object>> eformsFallback(ServerWebExchange exchange) {
        logFallback("ecm-eforms", exchange);
        return buildFallback("The forms service is temporarily unavailable.", "EFORMS_SERVICE_UNAVAILABLE");
    }

    @RequestMapping("/fallback/admin")
    public ResponseEntity<Map<String, Object>> adminFallback(ServerWebExchange exchange) {
        logFallback("ecm-admin", exchange);
        return buildFallback("The admin service is temporarily unavailable.", "ADMIN_SERVICE_UNAVAILABLE");
    }

    @RequestMapping("/fallback/ocr")
    public ResponseEntity<Map<String, Object>> ocrFallback(ServerWebExchange exchange) {
        logFallback("ecm-ocr", exchange);
        return buildFallback("The OCR service is temporarily unavailable.", "OCR_SERVICE_UNAVAILABLE");
    }

    // ── Generic fallback for any unmatched route ──────────────────────────────

    @RequestMapping("/fallback")
    public ResponseEntity<Map<String, Object>> genericFallback(ServerWebExchange exchange) {
        logFallback("unknown-service", exchange);
        return buildFallback("Service temporarily unavailable. Please try again.", "SERVICE_UNAVAILABLE");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void logFallback(String service, ServerWebExchange exchange) {
        log.warn("Circuit breaker OPEN for service={} path={} correlationId={}",
                service,
                exchange.getRequest().getPath(),
                exchange.getRequest().getHeaders()
                        .getFirst("X-Correlation-ID"));
    }

    private ResponseEntity<Map<String, Object>> buildFallback(String message, String errorCode) {
        // Shape matches ecm-common ApiResponse so frontend error handling is uniform
        Map<String, Object> body = Map.of(
                "success",   false,
                "message",   message,
                "errorCode", errorCode,
                "timestamp", OffsetDateTime.now().toString()
        );
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }
}