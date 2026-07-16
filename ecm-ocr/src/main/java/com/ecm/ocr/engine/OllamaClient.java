package com.ecm.ocr.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

/**
 * HTTP client for the Ollama REST API.
 *
 * <h3>Memory safety (16 GB laptop):</h3>
 * <ul>
 *   <li>Single shared HttpClient — no per-request allocation</li>
 *   <li>Streaming disabled ({@code "stream": false}) — Ollama buffers internally,
 *       we receive one JSON response instead of parsing NDJSON chunks</li>
 *   <li>Connection timeout 10s, request timeout configurable (default 120s)</li>
 *   <li>Images sent as base64 in the JSON body (Ollama API requirement for vision models)</li>
 *   <li>No retry loop — fail fast, let pipeline fall through to next engine</li>
 * </ul>
 *
 * <h3>Ollama memory management:</h3>
 * <p>GLM-OCR is 0.9B params / ~2.2 GB VRAM. On a 16 GB Mac with unified memory,
 * Ollama will use ~3 GB (model + KV cache). We set {@code OLLAMA_NUM_PARALLEL=1}
 * and {@code OLLAMA_MAX_LOADED_MODELS=1} to prevent multiple models competing
 * for memory. The pipeline already processes one document at a time (prefetchCount=1).</p>
 *
 * @see <a href="https://github.com/ollama/ollama/blob/main/docs/api.md">Ollama API docs</a>
 */
@Slf4j
@Component
public class OllamaClient {

    private final ObjectMapper objectMapper;

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public OllamaClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Send an image to an Ollama vision model and get a text response.
     *
     * @param ollamaUrl  base URL (e.g. "http://localhost:11434")
     * @param model      model name (e.g. "glm-ocr")
     * @param prompt     text prompt
     * @param imageBytes raw image bytes (PNG/JPEG) — base64-encoded for Ollama
     * @param timeoutSec request timeout in seconds
     * @return raw text response from the model
     */
    public String generate(String ollamaUrl, String model, String prompt,
                           byte[] imageBytes, int timeoutSec) {

        try {
            String base64Image = Base64.getEncoder().encodeToString(imageBytes);

            // Build request JSON — Ollama /api/generate format
            // stream:false = single JSON response (no NDJSON chunks)
            // keep_alive:5m = unload model after 5 min idle (frees memory)
            String requestBody = objectMapper.writeValueAsString(Map.of(
                    "model", model,
                    "prompt", prompt,
                    "images", new String[]{base64Image},
                    "stream", false,
                    "options", Map.of(
                            "temperature", 0.0,      // near-deterministic for OCR
                            "num_predict", 4096,      // max output tokens
                            "num_ctx", 8192           // context window (conservative for 16GB)
                    ),
                    "keep_alive", "5m"                // unload after 5 min idle → frees ~3GB
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaUrl.replaceAll("/$", "") + "/api/generate"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(timeoutSec))
                    .build();

            log.debug("Ollama request: model={}, promptLen={}, imageSize={} bytes, timeout={}s",
                    model, prompt.length(), imageBytes.length, timeoutSec);

            long start = System.currentTimeMillis();
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            long elapsed = System.currentTimeMillis() - start;

            if (response.statusCode() != 200) {
                log.error("Ollama returned HTTP {}: {}", response.statusCode(),
                        truncate(response.body(), 500));
                return "";
            }

            JsonNode root = objectMapper.readTree(response.body());
            String text = root.path("response").asText("");

            log.info("Ollama response: model={}, elapsed={}ms, responseLen={}",
                    model, elapsed, text.length());
            return text;

        } catch (java.net.http.HttpTimeoutException e) {
            log.warn("Ollama request timed out after {}s: {}", timeoutSec, e.getMessage());
            return "";
        } catch (java.net.ConnectException e) {
            log.warn("Ollama not reachable at {}: {}", ollamaUrl, e.getMessage());
            return "";
        } catch (Exception e) {
            log.error("Ollama request failed: {}", e.getMessage(), e);
            return "";
        }
    }

    /**
     * Send a text-only prompt (no image) — used for classification from prior OCR text.
     */
    public String generateText(String ollamaUrl, String model, String prompt, int timeoutSec) {
        try {
            String requestBody = objectMapper.writeValueAsString(Map.of(
                    "model", model,
                    "prompt", prompt,
                    "stream", false,
                    "format", "json",
                    "options", Map.of(
                            "temperature", 0.0,
                            "num_predict", 4096,
                            "num_ctx", 8192
                    ),
                    "keep_alive", "5m"
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaUrl.replaceAll("/$", "") + "/api/generate"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(timeoutSec))
                    .build();

            long start = System.currentTimeMillis();
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            long elapsed = System.currentTimeMillis() - start;

            if (response.statusCode() != 200) {
                log.error("Ollama text request returned HTTP {}: {}", response.statusCode(),
                        truncate(response.body(), 500));
                return "";
            }

            JsonNode root = objectMapper.readTree(response.body());
            String text = root.path("response").asText("");
            log.info("Ollama text response: model={}, elapsed={}ms, responseLen={}", model, elapsed, text.length());
            return text;

        } catch (Exception e) {
            log.warn("Ollama text request failed: {}", e.getMessage());
            return "";
        }
    }

    /**
     * Test connection to Ollama and check if the specified model is available.
     *
     * @param ollamaUrl base URL
     * @param model     model name to check
     * @return test result
     */
    public ConnectionTestResult testConnection(String ollamaUrl, String model) {
        try {
            long start = System.currentTimeMillis();

            // Step 1: Check Ollama is running (GET /api/tags)
            HttpRequest tagsRequest = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaUrl.replaceAll("/$", "") + "/api/tags"))
                    .GET()
                    .timeout(Duration.ofSeconds(5))
                    .build();

            HttpResponse<String> tagsResponse = HTTP_CLIENT.send(tagsRequest, HttpResponse.BodyHandlers.ofString());
            long latency = System.currentTimeMillis() - start;

            if (tagsResponse.statusCode() != 200) {
                return ConnectionTestResult.fail("Ollama returned HTTP " + tagsResponse.statusCode());
            }

            // Step 2: Check if the model exists
            JsonNode tags = objectMapper.readTree(tagsResponse.body());
            JsonNode models = tags.path("models");
            boolean modelFound = false;
            String modelSize = "";

            if (models.isArray()) {
                for (JsonNode m : models) {
                    String name = m.path("name").asText("");
                    // Match "glm-ocr" against "glm-ocr:latest"
                    if (name.equals(model) || name.startsWith(model + ":")) {
                        modelFound = true;
                        long sizeBytes = m.path("size").asLong(0);
                        modelSize = String.format("%.1f GB", sizeBytes / 1_073_741_824.0);
                        break;
                    }
                }
            }

            if (!modelFound) {
                return ConnectionTestResult.fail(
                        "Connected to Ollama but model '" + model + "' not found. Run: ollama pull " + model);
            }

            return ConnectionTestResult.ok(
                    "Connected. Model " + model + " available (" + modelSize + ")", latency);

        } catch (java.net.ConnectException e) {
            return ConnectionTestResult.fail("Cannot connect to " + ollamaUrl + ". Is Ollama running?");
        } catch (Exception e) {
            return ConnectionTestResult.fail("Connection failed: " + e.getMessage());
        }
    }

    private static String truncate(String s, int maxLen) {
        return s != null && s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}
