package com.ecm.ocr.engine;

import com.ecm.ocr.aigateway.AiGatewayConfigService;
import com.ecm.ocr.aigateway.AiGatewayInvokeClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

/**
 * Text classify+extract engine ({@code engineId="llama-text"}) — classifies documents
 * and extracts fields from OCR text produced by a prior engine (vision OCR or RapidOCR).
 *
 * <p>This is NOT a vision model — it works on already-extracted text. Like
 * {@link GlmOcrEngine}, model selection is NOT hardcoded here: when AI Gateway
 * routing is enabled ({@code ecm.ocr.route=gateway}), the gateway resolves
 * whichever model is configured for the {@code ecm-classification} application in
 * its own admin UI (see {@code ai_config.models} /
 * {@code tenant_application_model_scope} in the AI Gateway's database) — it is
 * NOT necessarily Llama 3.2 despite the class/engine name, which predates gateway
 * routing. Falls back to direct Ollama (using the {@code model} config field,
 * default {@code llama3.2:3b}) only if the gateway is disabled or unreachable.
 *
 * <p>Pipeline position: after the vision OCR step (text extraction), before Azure (fallback).</p>
 */
@Slf4j
@Component
public class LlamaTextEngine implements OcrEnginePlugin {

    private final OllamaClient ollamaClient;
    private final GlmPromptBuilder promptBuilder;
    private final ObjectMapper objectMapper;
    private final AiGatewayConfigService aiGatewayConfig;
    private final AiGatewayInvokeClient aiGatewayClient;

    /** Echoed prompt-example values to discard — kept in sync with GlmPromptBuilder's example. */
    private static final Set<String> PLACEHOLDER_VALUES = Set.of(
            "null", "value", "john smith", "mary ann jane smith",
            "mary", "ann jane", "smith", "1990-01-15", "2028-06-30", "a1234567");

    /** Always allowed regardless of category field list — needed for name synthesis. */
    private static final Set<String> NAME_SYNTHESIS_FIELDS = Set.of(
            "full_name", "first_name", "middle_name", "last_name");

    public LlamaTextEngine(OllamaClient ollamaClient,
                           GlmPromptBuilder promptBuilder,
                           ObjectMapper objectMapper,
                           AiGatewayConfigService aiGatewayConfig,
                           AiGatewayInvokeClient aiGatewayClient) {
        this.ollamaClient = ollamaClient;
        this.promptBuilder = promptBuilder;
        this.objectMapper = objectMapper;
        this.aiGatewayConfig = aiGatewayConfig;
        this.aiGatewayClient = aiGatewayClient;
    }

    @Override
    public String engineId() { return "llama-text"; }

    @Override
    public String displayName() { return "Text Classify + Extract (via AI Gateway)"; }

    @Override
    public Set<Capability> capabilities() {
        return Set.of(Capability.CLASSIFY, Capability.EXTRACT_FIELDS);
    }

    @Override
    public OcrEngineResult process(byte[] imageBytes, String contentType, EngineContext ctx) {
        // This engine works on TEXT, not images
        String previousText = ctx.previousText();
        if (previousText == null || previousText.length() < 30) {
            log.info("Llama-text: no previous text available for docId={}", ctx.documentId());
            return OcrEngineResult.empty(engineId());
        }

        String model = ctx.engineConfig().getOrDefault("model", "llama3.2:3b");

        // Build classification + extraction prompt (same for both routes — ECM owns this prompt)
        String prompt = promptBuilder.buildTextPrompt(ctx);

        log.debug("Llama-text request: model={}, textLen={}, docId={}",
                model, previousText.length(), ctx.documentId());

        // Check routing flag — if gateway mode + creds configured, try the gateway path first.
        // On any gateway failure (auth, PII block, network, etc.), fall back to direct Ollama
        // so the document still gets processed. The fallback is observable via WARN log lines.
        if (aiGatewayConfig.shouldRouteViaGateway()) {
            try {
                String rawResponse = invokeViaGateway(prompt, ctx);
                if (rawResponse != null && !rawResponse.isBlank()) {
                    log.info("Llama-text: routed via AI Gateway, responseLen={}, docId={}",
                            rawResponse.length(), ctx.documentId());
                    return parseResponse(rawResponse, ctx);
                }
                log.warn("Llama-text: AI Gateway returned empty response for docId={}, falling back to direct",
                        ctx.documentId());
            } catch (AiGatewayInvokeClient.AiGatewayPiiBlockedException pii) {
                log.warn("Llama-text: AI Gateway blocked ({} PII: {}) for docId={}, falling back to direct Ollama",
                        pii.direction(), pii.categories(), ctx.documentId());
            } catch (AiGatewayInvokeClient.AiGatewayInvokeException e) {
                log.warn("Llama-text: AI Gateway call failed for docId={}: {} — falling back to direct Ollama",
                        ctx.documentId(), e.getMessage());
            } catch (Exception e) {
                log.warn("Llama-text: unexpected AI Gateway error for docId={}: {} — falling back",
                        ctx.documentId(), e.getMessage());
            }
            // Fall through to direct Ollama on any gateway failure
        }

        // Direct Ollama path (existing behavior)
        String url = ctx.engineConfig().getOrDefault("url", "http://localhost:11434");
        int timeout = Integer.parseInt(ctx.engineConfig().getOrDefault("timeout", "60"));

        String rawResponse = ollamaClient.generateText(url, model, prompt, timeout);

        if (rawResponse == null || rawResponse.isBlank()) {
            log.warn("Llama-text returned empty response for docId={}", ctx.documentId());
            return OcrEngineResult.empty(engineId());
        }

        return parseResponse(rawResponse, ctx);
    }

    /**
     * Invoke the AI Gateway {@code /api/invoke} endpoint with the rendered prompt.
     * Returns the raw JSON-string model output (to be parsed by {@link #parseResponse}
     * exactly as the direct-Ollama path does).
     *
     * <p>Model selection is intentionally NOT overridden — the gateway resolves the
     * default text model from its per-application configuration. This lets the platform
     * admin swap models (e.g. qwen2.5:7b → qwen2.5:14b) from the AI Gateway admin UI
     * without any ECM code or config change.
     */
    private String invokeViaGateway(String prompt, EngineContext ctx) {
        AiGatewayInvokeClient.InvokeResponse resp = aiGatewayClient.invokeText(
                "llama-text:" + ctx.documentId(),
                prompt,
                "JSON",           // we want the gateway to parse JSON for us
                null);            // let the gateway pick the text model from app config
        return resp.text();
    }

    @Override
    public ConnectionTestResult testConnection(Map<String, String> config) {
        String url = config.getOrDefault("url", "http://localhost:11434");
        String model = config.getOrDefault("model", "llama3.2:3b");
        return ollamaClient.testConnection(url, model);
    }

    private OcrEngineResult parseResponse(String raw, EngineContext ctx) {
        try {
            String json = extractJson(raw);
            if (json == null) {
                log.warn("Llama-text: no JSON found in response for docId={}", ctx.documentId());
                return OcrEngineResult.empty(engineId());
            }

            JsonNode root = objectMapper.readTree(json);

            // Category
            String category = root.has("category") ? root.path("category").asText(null) : null;
            if (category != null) category = category.toUpperCase().strip();

            // Confidence
            BigDecimal confidence = null;
            if (root.has("confidence")) {
                confidence = BigDecimal.valueOf(root.path("confidence").asDouble(0))
                        .setScale(2, java.math.RoundingMode.HALF_UP);
            }

            // Fields — filter out echoed prompt placeholders and fields the model
            // invented but was never asked for (e.g. a hallucinated "donor_name"
            // from an organ-donor icon on a driver's license).
            String effectiveCategory = ctx.categoryCode() != null ? ctx.categoryCode() : category;
            List<String> requestedFields = promptBuilder.getFieldsForCategory(effectiveCategory);
            Set<String> allowedFields = requestedFields.isEmpty() ? null : requestedFields.stream()
                    .map(f -> f.toLowerCase(Locale.ROOT))
                    .collect(java.util.stream.Collectors.toSet());

            Map<String, Object> fields = new LinkedHashMap<>();
            JsonNode fieldsNode = root.path("fields");
            if (fieldsNode.isObject()) {
                fieldsNode.fields().forEachRemaining(entry -> {
                    String key = entry.getKey();
                    JsonNode val = entry.getValue();

                    boolean isAllowed = allowedFields == null
                            || allowedFields.contains(key.toLowerCase(Locale.ROOT))
                            || NAME_SYNTHESIS_FIELDS.contains(key.toLowerCase(Locale.ROOT));
                    if (!isAllowed) {
                        log.debug("Llama-text: dropping unrequested field '{}' for docId={}", key, ctx.documentId());
                        return;
                    }

                    if (val.isTextual()) {
                        String v = val.asText();
                        if (!v.isBlank() && !PLACEHOLDER_VALUES.contains(v.toLowerCase(Locale.ROOT))) {
                            fields.put(key, v);
                        }
                    } else if (val.isNumber()) {
                        fields.put(key, val.asText());
                    }
                });
            }

            // Use previous text (this engine doesn't do OCR)
            String text = ctx.previousText();

            log.info("Llama-text parsed: category={}, confidence={}, fields={}, docId={}",
                    category, confidence, fields.size(), ctx.documentId());

            return new OcrEngineResult(text, fields, category, confidence,
                    List.of(), engineId(), "llama3.2:3b");

        } catch (Exception e) {
            log.warn("Llama-text parsing failed for docId={}: {}. Raw: {}",
                    ctx.documentId(), e.getMessage(),
                    raw.length() > 300 ? raw.substring(0, 300) : raw);
            return OcrEngineResult.empty(engineId());
        }
    }

    private String extractJson(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String trimmed = raw.strip();

        if (trimmed.startsWith("{")) return trimmed;

        // Markdown fences
        int firstBrace = trimmed.indexOf('{');
        int lastBrace = trimmed.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return trimmed.substring(firstBrace, lastBrace + 1);
        }
        return null;
    }
}
