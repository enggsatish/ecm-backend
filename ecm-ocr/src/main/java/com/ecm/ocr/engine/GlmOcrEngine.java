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
 * Vision LLM engine plugin — routes document images through the AI Gateway for OCR,
 * classification, and field extraction. The gateway selects a vision-capable model
 * (any model with {@code supports_vision=true} in {@code ai_config.models}).
 *
 * <p>The engine ID remains {@code "glm-ocr"} for backwards compatibility with existing
 * pipeline configurations stored in tenant_config.</p>
 *
 * <h3>Capabilities:</h3>
 * <ul>
 *   <li>OCR — extracts text from images</li>
 *   <li>CLASSIFY — identifies document category with confidence</li>
 *   <li>EXTRACT_FIELDS — extracts structured fields (name, DOB, etc.)</li>
 * </ul>
 *
 * <h3>Two routing modes:</h3>
 * <ol>
 *   <li><b>AI Gateway (primary)</b>: routes via {@code POST /api/invoke} when
 *       {@code ecm.ocr.route=gateway} in tenant_config. Gateway picks model by
 *       {@code supports_vision=true}. Provides model governance, usage logging, PII guard.</li>
 *   <li><b>Direct Ollama (fallback)</b>: used only if gateway is disabled or unreachable.
 *       Calls Ollama directly using the {@code model} config field (default: {@code glm-ocr}).</li>
 * </ol>
 *
 * <h3>Fallback memory considerations (16 GB Mac):</h3>
 * <ul>
 *   <li>GLM-OCR 0.9B ≈ 2.2 GB model + ~1 GB KV cache = ~3 GB total</li>
 *   <li>{@code keep_alive=5m} — model unloaded after 5 min idle, freeing memory</li>
 *   <li>{@code num_ctx=8192} — conservative context window to limit KV cache</li>
 *   <li>One request at a time (pipeline prefetchCount=1)</li>
 * </ul>
 */
@Slf4j
@Component
public class GlmOcrEngine implements OcrEnginePlugin {

    private final OllamaClient ollamaClient;
    private final GlmPromptBuilder promptBuilder;
    private final ObjectMapper objectMapper;
    private final AiGatewayConfigService aiGatewayConfig;
    private final AiGatewayInvokeClient aiGatewayClient;

    public GlmOcrEngine(OllamaClient ollamaClient,
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
    public String engineId() { return "glm-ocr"; }

    @Override
    public String displayName() { return "Vision LLM (via AI Gateway)"; }

    @Override
    public Set<Capability> capabilities() {
        // GLM-OCR is an OCR model — good at reading text from images.
        // Classification and field extraction are handled by Llama 3.2 (instruction model).
        // Do NOT add CLASSIFY or EXTRACT_FIELDS here — GLM-OCR echoes prompt examples
        // instead of extracting real values when asked for structured JSON output.
        return Set.of(Capability.OCR);
    }

    // Pure OCR prompt — no JSON, no classification, just read text. Used on both
    // the direct Ollama path and the AI Gateway path.
    private static final String VISION_TEXT_EXTRACTION_PROMPT =
            "Extract all visible text from this document image. " +
            "Return ONLY the raw text, preserving the layout. No JSON, no explanation.";

    @Override
    public OcrEngineResult process(byte[] imageBytes, String contentType, EngineContext ctx) {
        boolean hasImage = imageBytes != null && imageBytes.length > 0;
        if (!hasImage) {
            log.info("GLM-OCR: no image provided for documentId={}, skipping", ctx.documentId());
            return OcrEngineResult.empty(engineId());
        }

        String model = resolveConfig(ctx, "model", "glm-ocr");

        // Try AI Gateway path first if routing is enabled. On any failure fall through
        // to direct Ollama so the document still gets processed.
        if (aiGatewayConfig.shouldRouteViaGateway()) {
            try {
                String rawResponse = invokeViaGateway(imageBytes, contentType, ctx);
                if (rawResponse != null && !rawResponse.isBlank()) {
                    String text = flattenToPlainText(rawResponse.strip());
                    log.info("GLM-OCR: routed via AI Gateway, {} chars (raw={}), docId={}",
                            text.length(), rawResponse.length(), ctx.documentId());
                    return OcrEngineResult.textOnly(text, engineId());
                }
                log.warn("GLM-OCR: AI Gateway returned empty response for docId={}, falling back to direct",
                        ctx.documentId());
            } catch (AiGatewayInvokeClient.AiGatewayPiiBlockedException pii) {
                log.warn("GLM-OCR: AI Gateway blocked ({} PII: {}) for docId={}, falling back to direct Ollama",
                        pii.direction(), pii.categories(), ctx.documentId());
            } catch (AiGatewayInvokeClient.AiGatewayInvokeException e) {
                log.warn("GLM-OCR: AI Gateway call failed for docId={}: {} — falling back to direct Ollama",
                        ctx.documentId(), e.getMessage());
            } catch (Exception e) {
                log.warn("GLM-OCR: unexpected AI Gateway error for docId={}: {} — falling back",
                        ctx.documentId(), e.getMessage());
            }
            // Fall through to direct Ollama
        }

        // Direct Ollama path (existing behavior)
        String url = resolveConfig(ctx, "url", "http://localhost:11434");
        int timeout = Integer.parseInt(resolveConfig(ctx, "timeout", "120"));

        String rawResponse = ollamaClient.generate(url, model, VISION_TEXT_EXTRACTION_PROMPT, imageBytes, timeout);

        if (rawResponse == null || rawResponse.isBlank()) {
            log.warn("GLM-OCR returned empty response for documentId={}", ctx.documentId());
            return OcrEngineResult.empty(engineId());
        }

        String text = flattenToPlainText(rawResponse.strip());
        log.info("GLM-OCR FLATTENED {} chars (raw={} chars) for documentId={}",
                text.length(), rawResponse.length(), ctx.documentId());
        return OcrEngineResult.textOnly(text, engineId());
    }

    /**
     * Invoke the AI Gateway {@code /api/invoke} endpoint with the image bytes as a
     * multimodal attachment. Uses TEXT response format because we want raw text output.
     *
     * <p>Model selection is intentionally NOT overridden — the gateway resolves the
     * right vision-capable model from its per-application configuration. This lets the
     * platform admin swap models (e.g. glm-ocr → qwen2.5-vl) from the AI Gateway admin
     * UI without any ECM code or config change.
     */
    private String invokeViaGateway(byte[] imageBytes, String contentType, EngineContext ctx) {
        String mimeType = (contentType != null && !contentType.isBlank()) ? contentType : "image/png";
        AiGatewayInvokeClient.InvokeResponse resp = aiGatewayClient.invokeVision(
                "glm-ocr:" + ctx.documentId(),
                VISION_TEXT_EXTRACTION_PROMPT,
                imageBytes,
                mimeType,
                "TEXT",       // raw text output, no JSON parsing on the gateway side
                null);        // let the gateway pick the vision model from app config
        return resp.text();
    }

    @Override
    public ConnectionTestResult testConnection(Map<String, String> config) {
        String url = config.getOrDefault("url", "http://localhost:11434");
        String model = config.getOrDefault("model", "glm-ocr");
        return ollamaClient.testConnection(url, model);
    }

    /**
     * Parse GLM-OCR's JSON response into an OcrEngineResult.
     * Handles: clean JSON, markdown-wrapped JSON, and malformed responses.
     */
    private OcrEngineResult parseResponse(String raw, EngineContext ctx, boolean hasImage) {
        try {
            String json = extractJson(raw);
            if (json == null) {
                log.warn("GLM-OCR: could not find JSON in response for docId={}. Returning raw text.",
                        ctx.documentId());
                return extractTextFallback(raw, ctx);
            }

            JsonNode root = objectMapper.readTree(json);

            // Extract text
            String text = root.path("text").asText(ctx.previousText());

            // Extract category + confidence
            String category = root.has("category") ? root.path("category").asText(null) : ctx.categoryCode();
            BigDecimal confidence = null;
            if (root.has("confidence")) {
                confidence = BigDecimal.valueOf(root.path("confidence").asDouble(0))
                        .setScale(2, java.math.RoundingMode.HALF_UP);
            }

            // Extract fields — handle both flat object and nested structures
            Map<String, Object> fields = new LinkedHashMap<>();
            JsonNode fieldsNode = root.path("fields");
            if (fieldsNode.isObject()) {
                fieldsNode.fields().forEachRemaining(entry -> {
                    JsonNode val = entry.getValue();
                    // Skip nested objects/arrays (malformed GLM-OCR output)
                    if (val.isTextual()) {
                        String v = val.asText();
                        if (!v.isBlank() && !"null".equals(v) && !"value".equals(v)) {
                            fields.put(entry.getKey(), v);
                        }
                    } else if (val.isNumber()) {
                        fields.put(entry.getKey(), val.asText());
                    }
                });
            }

            // Extract regions (GLM-OCR's json_result with bbox_2d)
            List<OcrEngineResult.Region> regions = new ArrayList<>();
            JsonNode regionsNode = root.path("regions");
            if (!regionsNode.isMissingNode() && regionsNode.isArray()) {
                for (JsonNode r : regionsNode) {
                    String label = r.path("label").asText("text");
                    String content = r.path("content").asText("");
                    JsonNode bboxNode = r.path("bbox");
                    int[] bbox = new int[4];
                    if (bboxNode.isArray() && bboxNode.size() >= 4) {
                        for (int i = 0; i < 4; i++) bbox[i] = bboxNode.get(i).asInt(0);
                    }
                    regions.add(new OcrEngineResult.Region(label, content, bbox));
                }
            }

            log.info("GLM-OCR parsed: category={}, confidence={}, fields={}, regions={}, textLen={}, docId={}",
                    category, confidence, fields.size(), regions.size(), text.length(), ctx.documentId());

            return new OcrEngineResult(text, fields, category, confidence, regions, engineId(), "glm-ocr");

        } catch (Exception e) {
            log.warn("GLM-OCR response parsing failed for documentId={}: {}. Raw (first 500 chars): {}",
                    ctx.documentId(), e.getMessage(),
                    raw.length() > 500 ? raw.substring(0, 500) : raw);
            return extractTextFallback(raw, ctx);
        }
    }

    /**
     * Extract JSON from a response that may be wrapped in markdown code fences,
     * have leading/trailing text, or contain multiple JSON blocks.
     */
    private String extractJson(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String trimmed = raw.strip();

        // Case 1: clean JSON (starts with {)
        if (trimmed.startsWith("{")) return trimmed;

        // Case 2: markdown fences — ```json\n{...}\n``` or ```\n{...}\n```
        // Use regex to find JSON block between fences
        java.util.regex.Matcher fenceMatcher = java.util.regex.Pattern
                .compile("```(?:json)?\\s*\\n(\\{.*?\\})\\s*```", java.util.regex.Pattern.DOTALL)
                .matcher(trimmed);
        if (fenceMatcher.find()) {
            return fenceMatcher.group(1).strip();
        }

        // Case 3: JSON somewhere in the text (find first { to last })
        int firstBrace = trimmed.indexOf('{');
        int lastBrace = trimmed.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return trimmed.substring(firstBrace, lastBrace + 1);
        }

        return null;
    }

    /**
     * When JSON parsing fails completely, still try to extract the text value
     * from the raw response so the pipeline has OCR text to work with.
     * Uses simple string operations (no regex) to avoid catastrophic backtracking.
     */
    private OcrEngineResult extractTextFallback(String raw, EngineContext ctx) {
        if (raw == null || raw.isBlank()) return OcrEngineResult.empty(engineId());

        // Extract "text" field value using indexOf (no regex — safe on any input size)
        String text = extractJsonStringField(raw, "text");
        String category = extractJsonStringField(raw, "category");
        BigDecimal confidence = extractJsonNumberField(raw, "confidence");

        if (text != null && !text.isBlank()) {
            log.info("GLM-OCR fallback: extracted {} chars of text, category={}, docId={}",
                    text.length(), category, ctx.documentId());
            return new OcrEngineResult(text, Map.of(), category, confidence,
                    List.of(), engineId(), "glm-ocr");
        }

        // Last resort — return the raw response stripped of markdown
        String cleaned = raw.replaceAll("```[a-z]*\\n?", "").strip();
        log.info("GLM-OCR fallback: no text field found, using cleaned response ({} chars), docId={}",
                cleaned.length(), ctx.documentId());
        return OcrEngineResult.textOnly(cleaned, engineId());
    }

    /** Extract a JSON string field value using indexOf — no regex, no backtracking risk. */
    private static String extractJsonStringField(String json, String fieldName) {
        String key = "\"" + fieldName + "\"";
        int keyPos = json.indexOf(key);
        if (keyPos < 0) return null;

        // Find the colon after the key
        int colonPos = json.indexOf(':', keyPos + key.length());
        if (colonPos < 0) return null;

        // Find the opening quote of the value
        int openQuote = json.indexOf('"', colonPos + 1);
        if (openQuote < 0) return null;

        // Find the closing quote (handle escaped quotes)
        int pos = openQuote + 1;
        while (pos < json.length()) {
            char c = json.charAt(pos);
            if (c == '\\') { pos += 2; continue; } // skip escaped char
            if (c == '"') break;
            pos++;
        }
        if (pos >= json.length()) return null;

        return json.substring(openQuote + 1, pos)
                .replace("\\n", "\n")
                .replace("\\t", "\t")
                .replace("\\\"", "\"");
    }

    /** Extract a JSON number field value using indexOf. */
    private static BigDecimal extractJsonNumberField(String json, String fieldName) {
        String key = "\"" + fieldName + "\"";
        int keyPos = json.indexOf(key);
        if (keyPos < 0) return null;

        int colonPos = json.indexOf(':', keyPos + key.length());
        if (colonPos < 0) return null;

        // Skip whitespace after colon
        int numStart = colonPos + 1;
        while (numStart < json.length() && json.charAt(numStart) == ' ') numStart++;

        // Read digits
        int numEnd = numStart;
        while (numEnd < json.length() && (Character.isDigit(json.charAt(numEnd)) || json.charAt(numEnd) == '.')) numEnd++;

        if (numEnd > numStart) {
            try {
                return new BigDecimal(json.substring(numStart, numEnd));
            } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    /**
     * Resolve engine-specific config from the context's engineConfig map.
     * Pipeline service injects config from the PipelineConfig.EngineEntry before calling.
     */
    /**
     * Flatten GLM-OCR's JSON response into plain text.
     * GLM-OCR returns inconsistent JSON structures — keys change between runs.
     * We discard keys and extract all string values + keys that contain text.
     */
    private String flattenToPlainText(String raw) {
        // Strip markdown fences
        String cleaned = raw;
        if (cleaned.startsWith("```")) {
            int nl = cleaned.indexOf('\n');
            int end = cleaned.lastIndexOf("```");
            if (nl > 0 && end > nl) cleaned = cleaned.substring(nl + 1, end).strip();
        }

        // If not JSON, return as-is
        if (!cleaned.startsWith("{") && !cleaned.startsWith("[")) {
            return cleaned;
        }

        // Parse JSON and extract all text values
        try {
            StringBuilder sb = new StringBuilder();
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(cleaned);
            collectText(root, sb);
            String result = sb.toString().strip();
            return result.isEmpty() ? cleaned : result;
        } catch (Exception e) {
            // Not valid JSON — return as plain text
            return cleaned;
        }
    }

    /** Recursively collect all text from JSON nodes — both keys and values. */
    private void collectText(com.fasterxml.jackson.databind.JsonNode node, StringBuilder sb) {
        if (node.isTextual()) {
            sb.append(node.asText().replace("\\n", "\n")).append("\n");
        } else if (node.isNumber()) {
            sb.append(node.asText()).append("\n");
        } else if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                String key = entry.getKey();
                // Some keys contain actual text (GLM-OCR puts text in keys sometimes)
                if (key.length() > 20 || key.contains("\n") || key.contains(",")) {
                    sb.append(key.replace("\\n", "\n")).append("\n");
                }
                collectText(entry.getValue(), sb);
            });
        } else if (node.isArray()) {
            for (com.fasterxml.jackson.databind.JsonNode item : node) {
                collectText(item, sb);
            }
        }
    }

    private String resolveConfig(EngineContext ctx, String key, String defaultValue) {
        if (ctx.engineConfig() != null) {
            String val = ctx.engineConfig().get(key);
            if (val != null && !val.isBlank()) return val;
        }
        return defaultValue;
    }
}
