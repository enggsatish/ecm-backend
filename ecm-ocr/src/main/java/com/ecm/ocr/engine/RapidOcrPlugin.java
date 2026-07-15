package com.ecm.ocr.engine;

import com.ecm.ocr.service.OcrHttpClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * RapidOCR engine plugin — fast, free, local text extraction.
 *
 * <p>Capabilities: OCR only (text extraction). Does NOT classify or extract
 * structured fields. Typically used as the first engine in the pipeline to
 * cheaply get text, then pass it to GLM-OCR or Azure for classification.</p>
 *
 * <p>Wraps the existing {@link OcrHttpClient} which supports RapidOCR,
 * PaddleOCR, and hertzg/tesseract-server response formats.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RapidOcrPlugin implements OcrEnginePlugin {

    private final OcrHttpClient ocrHttpClient;

    @Override
    public String engineId() { return "rapidocr"; }

    @Override
    public String displayName() { return "RapidOCR (Local)"; }

    @Override
    public Set<Capability> capabilities() {
        return Set.of(Capability.OCR);
    }

    @Override
    public OcrEngineResult process(byte[] imageBytes, String contentType, EngineContext ctx) {
        if (imageBytes == null || imageBytes.length == 0) {
            log.warn("RapidOCR: no image bytes for documentId={}", ctx.documentId());
            return OcrEngineResult.empty(engineId());
        }

        String text = ocrHttpClient.recognizeImage(imageBytes, contentType, ctx.documentId());

        if (text == null || text.isBlank()) {
            log.info("RapidOCR: no text extracted for documentId={}", ctx.documentId());
            return OcrEngineResult.empty(engineId());
        }

        log.info("RapidOCR: extracted {} chars for documentId={}", text.length(), ctx.documentId());
        return OcrEngineResult.textOnly(text, engineId());
    }

    @Override
    public ConnectionTestResult testConnection(Map<String, String> config) {
        String url = config.getOrDefault("url", "http://localhost:8884");
        String apiPath = config.getOrDefault("apiPath", "/ocr");

        try {
            long start = System.currentTimeMillis();

            // Simple health check — send a minimal request
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url.replaceAll("/$", "") + "/"))
                    .GET()
                    .timeout(Duration.ofSeconds(5))
                    .build();

            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            long latency = System.currentTimeMillis() - start;

            if (response.statusCode() < 500) {
                return ConnectionTestResult.ok("RapidOCR container is running at " + url, latency);
            } else {
                return ConnectionTestResult.fail("RapidOCR returned HTTP " + response.statusCode());
            }

        } catch (java.net.ConnectException e) {
            return ConnectionTestResult.fail("Cannot connect to " + url + ". Is the container running?");
        } catch (Exception e) {
            return ConnectionTestResult.fail("Connection failed: " + e.getMessage());
        }
    }
}
