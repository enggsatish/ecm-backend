package com.ecm.ocr.controller;

import com.ecm.ocr.engine.ConnectionTestResult;
import com.ecm.ocr.engine.OcrEnginePlugin;
import com.ecm.ocr.pipeline.PipelineConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * REST controller for OCR pipeline configuration and engine connectivity testing.
 *
 * <p>Used by Admin → Settings → OCR Engine tab to:</p>
 * <ul>
 *   <li>Test connection to Ollama, Azure, RapidOCR</li>
 *   <li>Load and save pipeline configuration</li>
 *   <li>List available engines with capabilities</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/ocr")
public class OcrConfigController {

    private final Map<String, OcrEnginePlugin> engineRegistry;
    private final PipelineConfig pipelineConfig;

    public OcrConfigController(List<OcrEnginePlugin> plugins, PipelineConfig pipelineConfig) {
        this.pipelineConfig = pipelineConfig;
        this.engineRegistry = new LinkedHashMap<>();
        plugins.forEach(p -> engineRegistry.put(p.engineId(), p));
    }

    /**
     * List all available engines with their capabilities.
     * Used by admin UI to build the pipeline configuration screen.
     */
    @GetMapping("/engines")
    public ResponseEntity<Map<String, Object>> listEngines() {
        List<Map<String, Object>> engines = new ArrayList<>();
        for (OcrEnginePlugin plugin : engineRegistry.values()) {
            engines.add(Map.of(
                    "engineId", plugin.engineId(),
                    "displayName", plugin.displayName(),
                    "capabilities", plugin.capabilities().stream()
                            .map(Enum::name).toList()
            ));
        }

        // Also load current pipeline config
        List<PipelineConfig.EngineEntry> currentConfig = pipelineConfig.loadAllEngines();

        return ResponseEntity.ok(Map.of(
                "engines", engines,
                "pipeline", currentConfig
        ));
    }

    /**
     * Test connection to a specific engine.
     *
     * <pre>
     * POST /api/ocr/test-connection
     * {
     *   "engine": "glm-ocr",
     *   "config": { "url": "http://localhost:11434", "model": "glm-ocr" }
     * }
     * </pre>
     */
    @PostMapping("/test-connection")
    public ResponseEntity<ConnectionTestResult> testConnection(@RequestBody TestConnectionRequest request) {
        OcrEnginePlugin plugin = engineRegistry.get(request.engine());
        if (plugin == null) {
            return ResponseEntity.badRequest()
                    .body(ConnectionTestResult.fail("Unknown engine: " + request.engine()));
        }

        Map<String, String> config = request.config() != null ? request.config() : Map.of();
        log.info("Testing connection: engine={}, config keys={}", request.engine(), config.keySet());

        ConnectionTestResult result = plugin.testConnection(config);

        log.info("Connection test result: engine={}, success={}, message={}",
                request.engine(), result.success(), result.message());
        return ResponseEntity.ok(result);
    }

    record TestConnectionRequest(String engine, Map<String, String> config) {}
}
