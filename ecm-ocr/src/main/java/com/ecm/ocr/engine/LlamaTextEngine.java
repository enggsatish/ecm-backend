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
 * Llama 3.2 text engine — classifies documents and extracts fields from OCR text.
 *
 * <p>This is NOT a vision model. It receives text extracted by a prior engine
 * (GLM-OCR or RapidOCR) and uses Llama 3.2's instruction-following ability to:
 * <ul>
 *   <li>Classify the document into a category</li>
 *   <li>Extract structured fields (name, DOB, document number, etc.)</li>
 *   <li>Return valid JSON (Llama 3.2 is good at structured output)</li>
 * </ul>
 *
 * <p>Pipeline position: after GLM-OCR (text extraction), before Azure (fallback).</p>
 *
 * <h3>Memory (16 GB Mac):</h3>
 * <p>Llama 3.2 3B ≈ 3.5 GB loaded. Ollama auto-unloads GLM-OCR before loading this.
 * Sequential, not concurrent — pipeline processes one document at a time.</p>
 */
@Slf4j
@Component
public class LlamaTextEngine implements OcrEnginePlugin {

    private final OllamaClient ollamaClient;
    private final GlmPromptBuilder promptBuilder;
    private final ObjectMapper objectMapper;
    private final AiGatewayConfigService aiGatewayConfig;
    private final AiGatewayInvokeClient aiGatewayClient;

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
    public String displayName() { return "Llama 3.2 (Classify + Extract)"; }

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
                String rawResponse = invokeViaGateway(prompt, ctx, model);
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
     */
    private String invokeViaGateway(String prompt, EngineContext ctx, String model) {
        AiGatewayInvokeClient.InvokeResponse resp = aiGatewayClient.invokeText(
                "llama-text:" + ctx.documentId(),
                prompt,
                "JSON",           // we want the gateway to parse JSON for us
                model);           // pass the configured model as the override
        // Prefer the raw text field; parseResponse() can handle either.
        // The parsed tree is available in resp.parsed() if we want structured access later.
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

            // Fields
            Map<String, Object> fields = new LinkedHashMap<>();
            JsonNode fieldsNode = root.path("fields");
            if (fieldsNode.isObject()) {
                fieldsNode.fields().forEachRemaining(entry -> {
                    JsonNode val = entry.getValue();
                    if (val.isTextual()) {
                        String v = val.asText();
                        if (!v.isBlank() && !"null".equals(v) && !"value".equals(v)
                                && !"John Smith".equals(v) && !"Mary Jane Smith".equals(v)
                                && !"Mary Jane".equals(v) && !"Smith".equals(v)
                                && !"1990-01-15".equals(v) && !"2028-06-30".equals(v)
                                && !"A1234567".equals(v)) {
                            fields.put(entry.getKey(), v);
                        }
                    } else if (val.isNumber()) {
                        fields.put(entry.getKey(), val.asText());
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
