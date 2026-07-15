package com.ecm.ocr.pipeline;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Loads and manages the dynamic OCR pipeline configuration from tenant_config.
 *
 * <p>Pipeline config is a JSON array stored under key {@code ocr.pipeline}:</p>
 * <pre>
 * [
 *   {"engine":"glm-ocr","enabled":true,"priority":1,"minConfidence":75,
 *    "config":{"url":"http://localhost:11434","model":"glm-ocr","timeout":"120"}},
 *   {"engine":"azure","enabled":true,"priority":2,"minConfidence":0,
 *    "config":{"endpoint":"https://...","key":"...","rateLimit":"1"}},
 *   {"engine":"rapidocr","enabled":false,"priority":3,
 *    "config":{"url":"http://localhost:8884","apiPath":"/ocr","fileField":"image_file","timeout":"60"}}
 * ]
 * </pre>
 *
 * <p>If no pipeline config exists, returns a sensible default (RapidOCR → Azure)
 * so the system works without admin configuration (backwards compatible).</p>
 */
@Slf4j
@Component
public class PipelineConfig {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    private static final String CONFIG_KEY = "ocr.pipeline";

    public PipelineConfig(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * Load the ordered list of enabled engines from tenant_config.
     * Returns only enabled engines, sorted by priority.
     */
    public List<EngineEntry> loadEnabledEngines() {
        try {
            String json = jdbc.queryForObject(
                    "SELECT value FROM ecm_admin.tenant_config WHERE key = ?",
                    String.class, CONFIG_KEY);

            if (json != null && !json.isBlank()) {
                List<EngineEntry> all = objectMapper.readValue(json, new TypeReference<>() {});
                List<EngineEntry> enabled = all.stream()
                        .filter(EngineEntry::enabled)
                        .sorted((a, b) -> Integer.compare(a.priority(), b.priority()))
                        .toList();
                log.debug("Pipeline config loaded: {} engines enabled out of {}", enabled.size(), all.size());
                return enabled;
            }
        } catch (Exception e) {
            log.debug("No pipeline config in tenant_config — using defaults: {}", e.getMessage());
        }

        return defaultPipeline().stream()
                .filter(EngineEntry::enabled)
                .sorted((a, b) -> Integer.compare(a.priority(), b.priority()))
                .toList();
    }

    /**
     * Load all engines (including disabled) for admin UI display.
     */
    public List<EngineEntry> loadAllEngines() {
        try {
            String json = jdbc.queryForObject(
                    "SELECT value FROM ecm_admin.tenant_config WHERE key = ?",
                    String.class, CONFIG_KEY);

            if (json != null && !json.isBlank()) {
                return objectMapper.readValue(json, new TypeReference<>() {});
            }
        } catch (Exception e) {
            log.debug("No pipeline config — returning defaults");
        }

        return defaultPipeline();
    }

    /**
     * Default pipeline when no admin config exists.
     * Backwards compatible: RapidOCR (existing) → Azure (existing).
     * GLM-OCR included but disabled — admin enables it when Ollama is set up.
     */
    private List<EngineEntry> defaultPipeline() {
        return List.of(
                new EngineEntry("glm-ocr", false, 1, 75, Map.of(
                        "url", "http://localhost:11434",
                        "model", "glm-ocr",
                        "timeout", "120")),
                new EngineEntry("rapidocr", true, 2, 0, Map.of(
                        "url", "http://localhost:8884",
                        "apiPath", "/ocr",
                        "fileField", "image_file",
                        "timeout", "60")),
                new EngineEntry("azure", true, 3, 0, Map.of())
        );
    }

    /**
     * A single engine entry in the pipeline configuration.
     *
     * @param engine        engine ID ("glm-ocr", "azure", "rapidocr")
     * @param enabled       whether this engine is active in the pipeline
     * @param priority      execution order (lower = first)
     * @param minConfidence minimum confidence to accept result (0-100). 0 = always accept.
     * @param config        engine-specific config map (url, model, key, etc.)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EngineEntry(
            String engine,
            boolean enabled,
            int priority,
            int minConfidence,
            Map<String, String> config
    ) {}
}
