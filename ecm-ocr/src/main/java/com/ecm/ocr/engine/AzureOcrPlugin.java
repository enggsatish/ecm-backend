package com.ecm.ocr.engine;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * Azure Document Intelligence engine plugin.
 *
 * <p>Wraps the existing {@link AzureDocumentAiEngine} to conform to the
 * {@link OcrEnginePlugin} interface. Azure is typically used as a fallback
 * when GLM-OCR confidence is below threshold.</p>
 *
 * <p>Azure provides prebuilt models for structured document types:
 * IDENTITY → prebuilt-idDocument, INVOICE → prebuilt-invoice, etc.
 * When category is unknown, uses prebuilt-layout for general text extraction.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AzureOcrPlugin implements OcrEnginePlugin {

    private final AzureDocumentAiEngine azureEngine;

    @Override
    public String engineId() { return "azure"; }

    @Override
    public String displayName() { return "Azure AI Document Intelligence"; }

    @Override
    public Set<Capability> capabilities() {
        return Set.of(Capability.OCR, Capability.CLASSIFY, Capability.EXTRACT_FIELDS);
    }

    @Override
    public OcrEngineResult process(byte[] imageBytes, String contentType, EngineContext ctx) {
        if (imageBytes == null || imageBytes.length == 0) {
            log.warn("Azure: no image bytes for documentId={}", ctx.documentId());
            return OcrEngineResult.empty(engineId());
        }

        // Determine which Azure model to use
        String categoryCode = ctx.categoryCode() != null ? ctx.categoryCode() : "LAYOUT";

        AzureDocumentAiEngine.AzureOcrResult azResult =
                azureEngine.analyze(imageBytes, contentType, categoryCode, ctx.documentId());

        if (azResult.text().isBlank() && azResult.fields().isEmpty()) {
            return OcrEngineResult.empty(engineId());
        }

        // Azure doesn't classify — if we used a category-specific model
        // and got fields back, that's high confidence
        String detectedCategory = ctx.categoryCode();
        BigDecimal confidence = null;

        if (detectedCategory != null && !azResult.fields().isEmpty()) {
            // Category-specific model returned fields → high confidence
            int fieldCount = (int) azResult.fields().entrySet().stream()
                    .filter(e -> !e.getKey().startsWith("_")).count();
            confidence = BigDecimal.valueOf(Math.min(60 + fieldCount * 8, 99))
                    .setScale(2, java.math.RoundingMode.HALF_UP);
        }

        Map<String, Object> fields = new LinkedHashMap<>(azResult.fields());
        String modelUsed = "azure:" + categoryCode.toLowerCase();

        log.info("Azure result: category={}, fields={}, textLen={}, docId={}",
                categoryCode, fields.size(), azResult.text().length(), ctx.documentId());

        return new OcrEngineResult(azResult.text(), fields, detectedCategory,
                confidence, List.of(), engineId(), modelUsed);
    }

    @Override
    public ConnectionTestResult testConnection(Map<String, String> config) {
        String endpoint = config.get("endpoint");
        String key = config.get("key");

        if (endpoint == null || endpoint.isBlank() || key == null || key.isBlank()) {
            return ConnectionTestResult.fail("Azure endpoint and API key are required");
        }

        try {
            long start = System.currentTimeMillis();

            // Simple connectivity check — list models
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint.replaceAll("/$", "")
                            + "/documentintelligence/documentModels?api-version=2024-11-30"))
                    .header("Ocp-Apim-Subscription-Key", key)
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            long latency = System.currentTimeMillis() - start;

            if (response.statusCode() == 200) {
                return ConnectionTestResult.ok("Connected to Azure Document Intelligence", latency);
            } else if (response.statusCode() == 401 || response.statusCode() == 403) {
                return ConnectionTestResult.fail("Invalid API key (HTTP " + response.statusCode() + ")");
            } else {
                return ConnectionTestResult.fail("Azure returned HTTP " + response.statusCode());
            }

        } catch (Exception e) {
            return ConnectionTestResult.fail("Connection failed: " + e.getMessage());
        }
    }
}
