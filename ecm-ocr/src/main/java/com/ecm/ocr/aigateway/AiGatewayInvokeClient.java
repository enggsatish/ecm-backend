package com.ecm.ocr.aigateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP client for the AI Gateway's generic {@code POST /api/invoke} endpoint.
 *
 * <p>ECM owns prompts — this client just wraps HTTP plumbing. Callers pass in
 * an already-rendered prompt string, optional images, and the desired response
 * format. The gateway applies its governance pipeline (auth, tenancy, policy,
 * quotas, PII guard, token accounting) and returns the raw + parsed response.
 *
 * <h3>Error handling</h3>
 * <ul>
 *   <li><b>401 Unauthorized</b> — service JWT invalid or expired. Client
 *       invalidates the cached token and retries once. If the retry also fails,
 *       throws {@link AiGatewayInvokeException}.</li>
 *   <li><b>403 Forbidden</b> with PII input block — {@link AiGatewayPiiBlockedException}
 *       ({@code direction=input}). Callers should log, record a failed pipeline step,
 *       and fall back to direct Ollama.</li>
 *   <li><b>502 Bad Gateway</b> with PII output block — {@link AiGatewayPiiBlockedException}
 *       ({@code direction=output}). Same fallback.</li>
 *   <li><b>413 Payload Too Large</b> — prompt/image exceeds gateway limits.
 *       {@link AiGatewayInvokeException} with the limit in the message.</li>
 *   <li><b>5xx / timeout / connection error</b> — {@link AiGatewayInvokeException}.
 *       Callers fall back to direct Ollama.</li>
 * </ul>
 *
 * <p>None of the exceptions should propagate out of OCR — the engines catch them,
 * log a {@code PipelineStep.failed} entry on the document, and fall through to
 * their existing direct-Ollama path so the document still gets processed.
 */
@Slf4j
@Service
public class AiGatewayInvokeClient {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final AiGatewayConfigService configService;
    private final AiGatewayTokenService tokenService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public AiGatewayInvokeClient(AiGatewayConfigService configService,
                                 AiGatewayTokenService tokenService,
                                 ObjectMapper objectMapper) {
        this.configService = configService;
        this.tokenService = tokenService;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    /**
     * Send a text-only prompt to the gateway.
     *
     * @param correlationId  any string, echoed back + written to usage_log.input_preview
     * @param prompt         the final rendered prompt text (ECM did the template substitution)
     * @param responseFormat "TEXT" or "JSON"
     * @param modelOverride  optional model name, null to use gateway default
     * @return parsed response from the gateway
     */
    public InvokeResponse invokeText(String correlationId, String prompt,
                                     String responseFormat, String modelOverride) {
        return invoke(correlationId, prompt, null, responseFormat, modelOverride);
    }

    /**
     * Send a multimodal prompt with one or more images to the gateway.
     * Gateway will route to a {@code supports_vision=true} model.
     */
    public InvokeResponse invokeVision(String correlationId, String prompt,
                                        byte[] imageBytes, String mimeType,
                                        String responseFormat, String modelOverride) {
        List<InvokeImage> images = List.of(new InvokeImage(
                Base64.getEncoder().encodeToString(imageBytes), mimeType));
        return invoke(correlationId, prompt, images, responseFormat, modelOverride);
    }

    /** Underlying invoke — handles token acquisition, retry on 401, and error mapping. */
    private InvokeResponse invoke(String correlationId, String prompt,
                                  List<InvokeImage> images, String responseFormat,
                                  String modelOverride) {

        AiGatewayConfigService.CachedConfig cfg = configService.get();
        if (cfg.baseUrl() == null || cfg.baseUrl().isBlank()) {
            throw new AiGatewayInvokeException("AI Gateway base URL not configured");
        }

        String token = tokenService.getServiceToken();
        if (token == null) {
            throw new AiGatewayInvokeException("No service JWT available — credentials not configured");
        }

        // Try once with current token; on 401 invalidate and retry
        try {
            return doPost(cfg.baseUrl(), token, correlationId, prompt, images, responseFormat, modelOverride);
        } catch (AiGatewayAuthException e) {
            log.debug("AI Gateway returned 401, refreshing token and retrying once");
            tokenService.invalidateToken();
            String freshToken = tokenService.getServiceToken();
            if (freshToken == null) {
                throw new AiGatewayInvokeException("Token refresh returned null after 401");
            }
            return doPost(cfg.baseUrl(), freshToken, correlationId, prompt, images, responseFormat, modelOverride);
        }
    }

    private InvokeResponse doPost(String baseUrl, String token, String correlationId,
                                   String prompt, List<InvokeImage> images,
                                   String responseFormat, String modelOverride) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("correlationId", correlationId);
            body.put("prompt", prompt);
            if (images != null && !images.isEmpty()) {
                List<Map<String, Object>> imgs = new ArrayList<>();
                for (InvokeImage img : images) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("base64", img.base64());
                    m.put("mimeType", img.mimeType());
                    imgs.add(m);
                }
                body.put("images", imgs);
            }
            if (responseFormat != null) body.put("responseFormat", responseFormat);
            if (modelOverride != null && !modelOverride.isBlank()) body.put("model", modelOverride);

            String json = objectMapper.writeValueAsString(body);
            String url = baseUrl.replaceAll("/$", "") + "/api/invoke";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(REQUEST_TIMEOUT)
                    .build();

            long start = System.currentTimeMillis();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long elapsed = System.currentTimeMillis() - start;

            int status = response.statusCode();
            String respBody = response.body();

            if (status == 200) {
                InvokeResponse parsed = parseResponse(respBody);
                log.debug("AI Gateway invoke OK: correlationId={}, elapsed={}ms, model={}",
                        correlationId, elapsed, parsed.model());
                return parsed;
            }
            if (status == 401) {
                throw new AiGatewayAuthException("AI Gateway returned 401 Unauthorized");
            }
            if (status == 403) {
                String error = extractError(respBody);
                if (error != null && error.toLowerCase().contains("pii")) {
                    List<String> categories = extractPiiCategories(error);
                    log.warn("AI Gateway PII-blocked INPUT: correlationId={}, categories={}. " +
                                    "Falling back to direct. (Global pii.guard.input.action=BLOCK should not be set " +
                                    "for OCR; see project_ocr_via_ai_gateway_plan.md)",
                            correlationId, categories);
                    throw new AiGatewayPiiBlockedException("input", categories);
                }
                throw new AiGatewayInvokeException("AI Gateway returned 403: " + truncate(respBody, 300));
            }
            if (status == 413) {
                throw new AiGatewayInvokeException("AI Gateway returned 413 Payload Too Large: " +
                        truncate(respBody, 300));
            }
            if (status == 502) {
                String error = extractError(respBody);
                if (error != null && error.toLowerCase().contains("pii")) {
                    List<String> categories = extractPiiCategories(error);
                    log.warn("AI Gateway PII-blocked OUTPUT: correlationId={}, categories={}. Falling back.",
                            correlationId, categories);
                    throw new AiGatewayPiiBlockedException("output", categories);
                }
                throw new AiGatewayInvokeException("AI Gateway returned 502: " + truncate(respBody, 300));
            }
            // Any other status
            throw new AiGatewayInvokeException("AI Gateway returned HTTP " + status + ": " + truncate(respBody, 300));

        } catch (AiGatewayAuthException | AiGatewayInvokeException e) {
            // AiGatewayPiiBlockedException is a subclass of AiGatewayInvokeException and will be
            // caught by the second alternative above — so we don't list it separately.
            throw e;
        } catch (java.net.http.HttpTimeoutException e) {
            throw new AiGatewayInvokeException("AI Gateway request timed out after " + REQUEST_TIMEOUT.toSeconds() + "s", e);
        } catch (Exception e) {
            throw new AiGatewayInvokeException("AI Gateway request failed: " + e.getMessage(), e);
        }
    }

    private InvokeResponse parseResponse(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            String correlationId = root.path("correlationId").asText(null);
            String text = root.path("text").asText(null);
            JsonNode parsedNode = root.get("parsed"); // may be null when responseFormat=TEXT
            String model = root.path("model").asText(null);
            String provider = root.path("provider").asText(null);
            long durationMs = root.path("durationMs").asLong(0);
            long promptTokens = root.path("promptTokens").asLong(0);
            long completionTokens = root.path("completionTokens").asLong(0);
            long totalTokens = root.path("totalTokens").asLong(0);
            double costUsd = root.path("costUsd").asDouble(0.0);
            return new InvokeResponse(correlationId, text, parsedNode, model, provider,
                    durationMs, promptTokens, completionTokens, totalTokens, costUsd);
        } catch (Exception e) {
            throw new AiGatewayInvokeException("Failed to parse AI Gateway response: " + e.getMessage(), e);
        }
    }

    /** Extracts the "error" field from a gateway error body, best-effort. */
    private String extractError(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            JsonNode root = objectMapper.readTree(body);
            return root.path("error").asText(null);
        } catch (Exception e) {
            return body;
        }
    }

    /** Parses "[SSN, EMAIL]" or similar out of a PII block error message. */
    private List<String> extractPiiCategories(String error) {
        List<String> out = new ArrayList<>();
        if (error == null) return out;
        int start = error.indexOf('[');
        int end = error.lastIndexOf(']');
        if (start >= 0 && end > start) {
            String inner = error.substring(start + 1, end);
            for (String s : inner.split(",")) {
                String trimmed = s.trim();
                if (!trimmed.isEmpty()) out.add(trimmed);
            }
        }
        return out;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    // ── Records ───────────────────────────────────────────────────────────────

    /** Multimodal image attachment for {@link #invokeVision}. */
    public record InvokeImage(String base64, String mimeType) {}

    /** Parsed response from {@code POST /api/invoke}. */
    public record InvokeResponse(
            String correlationId,
            String text,
            JsonNode parsed,
            String model,
            String provider,
            long durationMs,
            long promptTokens,
            long completionTokens,
            long totalTokens,
            double costUsd
    ) {}

    // ── Exceptions ────────────────────────────────────────────────────────────

    /** Generic failure — callers fall back to direct Ollama. */
    public static class AiGatewayInvokeException extends RuntimeException {
        public AiGatewayInvokeException(String message) { super(message); }
        public AiGatewayInvokeException(String message, Throwable cause) { super(message, cause); }
    }

    /** 401 Unauthorized — internal retry trigger, not exposed to callers. */
    private static class AiGatewayAuthException extends RuntimeException {
        public AiGatewayAuthException(String message) { super(message); }
    }

    /**
     * PII Guard blocked the call. Includes direction ({@code input} or {@code output})
     * and the list of detected PII categories (SSN, EMAIL, etc.) for audit logging.
     */
    public static class AiGatewayPiiBlockedException extends AiGatewayInvokeException {
        private final String direction;
        private final List<String> categories;

        public AiGatewayPiiBlockedException(String direction, List<String> categories) {
            super("PII Guard blocked " + direction + ": " + categories);
            this.direction = direction;
            this.categories = categories;
        }

        public String direction()         { return direction; }
        public List<String> categories()  { return categories; }
    }
}
