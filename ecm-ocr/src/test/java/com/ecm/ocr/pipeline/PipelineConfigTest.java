package com.ecm.ocr.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

/**
 * Tests for PipelineConfig — verifies dynamic engine loading from DB and defaults.
 */
@ExtendWith(MockitoExtension.class)
class PipelineConfigTest {

    @Mock JdbcTemplate jdbc;
    ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Returns default pipeline when no DB config exists")
    void defaultPipelineWhenNoConfig() {
        given(jdbc.queryForObject(anyString(), eq(String.class), any()))
                .willThrow(new EmptyResultDataAccessException(1));

        PipelineConfig config = new PipelineConfig(jdbc, objectMapper);
        List<PipelineConfig.EngineEntry> engines = config.loadEnabledEngines();

        // Default: glm-ocr(disabled), rapidocr(enabled), azure(enabled)
        // Only enabled engines returned
        assertThat(engines).allMatch(PipelineConfig.EngineEntry::enabled);
        assertThat(engines.stream().map(PipelineConfig.EngineEntry::engine).toList())
                .contains("rapidocr", "azure");
        // Verify sorted by priority
        for (int i = 1; i < engines.size(); i++) {
            assertThat(engines.get(i).priority()).isGreaterThanOrEqualTo(engines.get(i - 1).priority());
        }
    }

    @Test
    @DisplayName("Loads and filters enabled engines from DB JSON")
    void loadsFromDb() {
        String json = """
                [
                  {"engine":"glm-ocr","enabled":true,"priority":1,"minConfidence":75,"config":{"url":"http://localhost:11434"}},
                  {"engine":"llama-text","enabled":true,"priority":2,"minConfidence":75,"config":{}},
                  {"engine":"azure","enabled":false,"priority":3,"minConfidence":0,"config":{}}
                ]
                """;
        given(jdbc.queryForObject(anyString(), eq(String.class), eq("ocr.pipeline")))
                .willReturn(json);

        PipelineConfig config = new PipelineConfig(jdbc, objectMapper);
        List<PipelineConfig.EngineEntry> engines = config.loadEnabledEngines();

        assertThat(engines).hasSize(2); // azure disabled
        assertThat(engines.get(0).engine()).isEqualTo("glm-ocr");
        assertThat(engines.get(0).minConfidence()).isEqualTo(75);
        assertThat(engines.get(1).engine()).isEqualTo("llama-text");
    }

    @Test
    @DisplayName("Sorts engines by priority")
    void sortsByPriority() {
        String json = """
                [
                  {"engine":"azure","enabled":true,"priority":3,"minConfidence":0,"config":{}},
                  {"engine":"glm-ocr","enabled":true,"priority":1,"minConfidence":75,"config":{}},
                  {"engine":"llama-text","enabled":true,"priority":2,"minConfidence":75,"config":{}}
                ]
                """;
        given(jdbc.queryForObject(anyString(), eq(String.class), eq("ocr.pipeline")))
                .willReturn(json);

        PipelineConfig config = new PipelineConfig(jdbc, objectMapper);
        List<PipelineConfig.EngineEntry> engines = config.loadEnabledEngines();

        assertThat(engines.get(0).engine()).isEqualTo("glm-ocr");
        assertThat(engines.get(1).engine()).isEqualTo("llama-text");
        assertThat(engines.get(2).engine()).isEqualTo("azure");
    }

    @Test
    @DisplayName("loadAllEngines returns disabled engines too")
    void loadAllIncludesDisabled() {
        String json = """
                [
                  {"engine":"glm-ocr","enabled":true,"priority":1,"minConfidence":75,"config":{}},
                  {"engine":"azure","enabled":false,"priority":2,"minConfidence":0,"config":{}}
                ]
                """;
        given(jdbc.queryForObject(anyString(), eq(String.class), eq("ocr.pipeline")))
                .willReturn(json);

        PipelineConfig config = new PipelineConfig(jdbc, objectMapper);
        List<PipelineConfig.EngineEntry> all = config.loadAllEngines();

        assertThat(all).hasSize(2);
    }
}
