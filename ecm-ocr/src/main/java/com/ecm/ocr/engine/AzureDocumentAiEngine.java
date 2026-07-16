package com.ecm.ocr.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * Azure Document Intelligence (formerly Form Recognizer) OCR engine.
 *
 * Uses the REST API directly — no Azure SDK dependency required.
 *
 * Flow:
 *   1. POST document bytes to Azure analyze endpoint
 *   2. Poll the operation URL until analysis completes
 *   3. Extract text content + structured fields from the response
 *
 * Supports prebuilt models:
 *   - prebuilt-idDocument (driver's licenses, passports, ID cards)
 *   - prebuilt-invoice (invoices, bills)
 *   - prebuilt-read (general OCR — text, tables, paragraphs)
 *
 * The model selection is driven by document category → model mapping
 * hardcoded in MODEL_MAP. Endpoint and key are read from tenant_config DB
 * (Admin → Settings → OCR Engine).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AzureDocumentAiEngine {

    private final ObjectMapper objectMapper;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final AzureRateLimiter rateLimiter;

    private static final String AZURE_API_VERSION = "2024-11-30";

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final int MAX_POLL_ATTEMPTS = 30;
    private static final long POLL_INTERVAL_MS = 2000;

    /**
     * Analyze a document using Azure Document Intelligence.
     *
     * @param bytes        document binary content
     * @param contentType  MIME type (e.g., "image/jpeg", "application/pdf")
     * @param categoryCode document category code (e.g., "IDENTITY") — used to select the model
     * @param documentId   for log correlation
     * @return result containing extracted text and structured fields
     */
    public AzureOcrResult analyze(byte[] bytes, String contentType,
                                   String categoryCode, Object documentId) {

        // Resolve credentials from DB (admin UI). No application.yml fallback —
        // all Azure config is managed via Admin → Settings → OCR Engine.
        String endpoint = resolveConfig("ocr.azure.endpoint", null);
        String apiKey   = resolveConfig("ocr.azure.key", null);

        if (endpoint == null || endpoint.isBlank() || apiKey == null || apiKey.isBlank()) {
            log.error("Azure Document AI not configured — set endpoint and key in Admin → Settings → OCR Engine. documentId={}", documentId);
            return AzureOcrResult.empty();
        }

        // Select model based on category
        String modelId = resolveModel(categoryCode);
        log.info("Azure AI analyze: documentId={}, model={}, category={}, contentType={}, size={}",
                documentId, modelId, categoryCode, contentType, bytes.length);

        try {
            // 0. Rate limit — wait for permit before calling Azure
            rateLimiter.acquire();

            // 1. Submit analysis request
            String operationUrl = submitAnalysis(endpoint, apiKey, modelId, bytes, contentType);
            if (operationUrl == null) {
                log.error("Azure AI submit failed — no operation URL returned. documentId={}", documentId);
                return AzureOcrResult.empty();
            }

            // 2. Poll for result
            JsonNode result = pollForResult(operationUrl, apiKey, documentId);
            if (result == null) {
                log.error("Azure AI polling failed — no result returned. documentId={}", documentId);
                return AzureOcrResult.empty();
            }

            // 3. Extract text and fields
            return parseResult(result, modelId, documentId);

        } catch (Exception e) {
            log.error("Azure AI analysis failed: documentId={}, error={}", documentId, e.getMessage(), e);
            return AzureOcrResult.empty();
        }
    }

    // ── Step 1: Submit ──────────────────────────────────────────────────────

    private String submitAnalysis(String endpoint, String apiKey,
                                   String modelId, byte[] bytes, String contentType)
            throws Exception {

        String url = endpoint.replaceAll("/$", "")
                + "/documentintelligence/documentModels/" + modelId
                + ":analyze?api-version=" + AZURE_API_VERSION;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Ocp-Apim-Subscription-Key", apiKey)
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 202) {
            // Operation-Location header contains the polling URL
            String operationUrl = response.headers().firstValue("Operation-Location").orElse(null);
            log.debug("Azure AI analysis submitted: operationUrl={}", operationUrl);
            return operationUrl;
        } else {
            log.error("Azure AI submit failed: status={}, body={}", response.statusCode(), response.body());
            return null;
        }
    }

    // ── Step 2: Poll ────────────────────────────────────────────────────────

    private JsonNode pollForResult(String operationUrl, String apiKey, Object documentId)
            throws Exception {

        for (int attempt = 0; attempt < MAX_POLL_ATTEMPTS; attempt++) {
            Thread.sleep(POLL_INTERVAL_MS);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(operationUrl))
                    .header("Ocp-Apim-Subscription-Key", apiKey)
                    .GET()
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode body = objectMapper.readTree(response.body());

            String status = body.path("status").asText();
            log.debug("Azure AI poll: attempt={}, status={}, documentId={}", attempt + 1, status, documentId);

            if ("succeeded".equals(status)) {
                return body.path("analyzeResult");
            } else if ("failed".equals(status)) {
                log.error("Azure AI analysis failed: documentId={}, error={}",
                        documentId, body.path("error").toString());
                return null;
            }
            // "running" or "notStarted" — continue polling
        }

        log.error("Azure AI polling timed out after {} attempts for documentId={}",
                MAX_POLL_ATTEMPTS, documentId);
        return null;
    }

    // ── Step 3: Parse result ────────────────────────────────────────────────

    private AzureOcrResult parseResult(JsonNode analyzeResult, String modelId, Object documentId) {
        // Extract full text content
        String content = analyzeResult.path("content").asText("");

        // Extract structured fields from documents array
        Map<String, Object> fields = new LinkedHashMap<>();

        JsonNode documents = analyzeResult.path("documents");
        if (documents.isArray() && !documents.isEmpty()) {
            JsonNode firstDoc = documents.get(0);
            JsonNode docFields = firstDoc.path("fields");

            if (docFields.isObject()) {
                docFields.fields().forEachRemaining(entry -> {
                    String fieldName = entry.getKey();
                    JsonNode fieldNode = entry.getValue();

                    // Azure returns fields with type, content, and confidence
                    String value = fieldNode.path("content").asText(
                            fieldNode.path("valueString").asText(
                                    fieldNode.path("value").asText("")));

                    double confidence = fieldNode.path("confidence").asDouble(0);

                    if (!value.isBlank()) {
                        fields.put(camelToSnake(fieldName), value);
                        log.debug("  field: {}={} (confidence={:.2f})", fieldName, value, confidence);
                    }
                });
            }

            // Extract document type-specific metadata
            String docType = firstDoc.path("docType").asText("");
            if (!docType.isBlank()) {
                fields.put("_azure_doc_type", docType);
            }
        }

        log.info("Azure AI result: documentId={}, model={}, textChars={}, fields={}",
                documentId, modelId, content.length(), fields.size());

        return new AzureOcrResult(content, fields);
    }

    // ── Model selection ─────────────────────────────────────────────────────

    /** Category code → Azure prebuilt model mapping. */
    private static final Map<String, String> MODEL_MAP = Map.of(
            "IDENTITY",    "prebuilt-idDocument",
            "MORTGAGE",    "prebuilt-invoice",
            "INVOICE",     "prebuilt-invoice",
            "RECEIPT",     "prebuilt-receipt",
            "TAX",         "prebuilt-tax.us.w2",
            "LAYOUT",      "prebuilt-layout"
    );

    private String resolveModel(String categoryCode) {
        if (categoryCode != null) {
            String model = MODEL_MAP.get(categoryCode.toUpperCase());
            if (model != null) return model;
        }
        return "prebuilt-read"; // fallback: general OCR
    }

    /**
     * Whether this category has a field-extraction-capable prebuilt model,
     * as opposed to falling back to generic text-only OCR.
     */
    public boolean hasSpecificModel(String categoryCode) {
        return categoryCode != null && MODEL_MAP.containsKey(categoryCode.toUpperCase());
    }

    /** Converts camelCase field names to snake_case (Azure uses camelCase). */
    private String camelToSnake(String name) {
        return name.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
    }

    // ── Config resolution (DB first, then application.yml fallback) ────────

    /**
     * Reads a config value from tenant_config table first (admin UI),
     * falls back to the application.yml property value.
     */
    private String resolveConfig(String dbKey, String fallback) {
        try {
            String dbValue = jdbc.queryForObject(
                    "SELECT value FROM ecm_admin.tenant_config WHERE key = ?",
                    String.class, dbKey);
            if (dbValue != null && !dbValue.isBlank()) return dbValue.trim();
        } catch (Exception e) {
            // No DB row — use fallback
        }
        return fallback;
    }

    // ── Result DTO ──────────────────────────────────────────────────────────

    public record AzureOcrResult(String text, Map<String, Object> fields) {
        public static AzureOcrResult empty() { return new AzureOcrResult("", Map.of()); }
    }
}
