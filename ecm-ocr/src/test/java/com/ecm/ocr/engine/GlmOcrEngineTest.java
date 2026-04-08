package com.ecm.ocr.engine;

import com.ecm.ocr.aigateway.AiGatewayConfigService;
import com.ecm.ocr.aigateway.AiGatewayInvokeClient;
import com.ecm.ocr.pipeline.ExtractionTemplateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

/**
 * Unit tests for GlmOcrEngine — verifies OCR text extraction via Ollama,
 * response parsing, fallback handling, and capability declaration.
 */
@ExtendWith(MockitoExtension.class)
class GlmOcrEngineTest {

    @Mock OllamaClient ollamaClient;
    @Mock ExtractionTemplateService extractionTemplateService;
    @Mock AiGatewayConfigService aiGatewayConfig;
    @Mock AiGatewayInvokeClient aiGatewayClient;
    ObjectMapper objectMapper = new ObjectMapper();

    GlmPromptBuilder promptBuilder;
    GlmOcrEngine engine;

    @BeforeEach
    void setUp() {
        promptBuilder = new GlmPromptBuilder(extractionTemplateService);
        engine = new GlmOcrEngine(ollamaClient, promptBuilder, objectMapper, aiGatewayConfig, aiGatewayClient);
    }

    @Test
    @DisplayName("Engine ID is 'glm-ocr'")
    void engineId() {
        assertThat(engine.engineId()).isEqualTo("glm-ocr");
    }

    @Test
    @DisplayName("Capabilities include only OCR — no CLASSIFY or EXTRACT_FIELDS")
    void capabilitiesOcrOnly() {
        assertThat(engine.capabilities())
                .containsExactly(OcrEnginePlugin.Capability.OCR);
        assertThat(engine.capabilities())
                .doesNotContain(OcrEnginePlugin.Capability.CLASSIFY, OcrEnginePlugin.Capability.EXTRACT_FIELDS);
    }

    @Nested
    @DisplayName("process()")
    class Process {

        EngineContext ctx = EngineContext.initial(UUID.randomUUID(), "test.jpg", null, null)
                .withEngineConfig(Map.of("url", "http://localhost:11434", "model", "glm-ocr", "timeout", "60"));

        @Test
        @DisplayName("Returns extracted text from Ollama response")
        void extractsText() {
            given(ollamaClient.generate(anyString(), anyString(), anyString(), any(byte[].class), anyInt()))
                    .willReturn("ALBERTA\nGOVERNMENT\nERIN ANDERSON\nOPERATOR'S LICENCE");

            OcrEngineResult result = engine.process(new byte[]{1, 2, 3}, "image/jpeg", ctx);

            assertThat(result.text()).contains("ERIN ANDERSON");
            assertThat(result.text()).contains("OPERATOR'S LICENCE");
            assertThat(result.engineId()).isEqualTo("glm-ocr");
            assertThat(result.detectedCategory()).isNull(); // OCR only, no classification
            assertThat(result.fields()).isEmpty();
        }

        @Test
        @DisplayName("Returns empty when no image provided")
        void emptyWhenNoImage() {
            OcrEngineResult result = engine.process(null, "image/jpeg", ctx);
            assertThat(result.text()).isEmpty();
        }

        @Test
        @DisplayName("Returns empty when Ollama returns blank")
        void emptyWhenOllamaBlank() {
            given(ollamaClient.generate(anyString(), anyString(), anyString(), any(byte[].class), anyInt()))
                    .willReturn("");

            OcrEngineResult result = engine.process(new byte[]{1}, "image/jpeg", ctx);
            assertThat(result.text()).isEmpty();
        }

        @Test
        @DisplayName("Strips markdown fences from response")
        void stripsMarkdownFences() {
            given(ollamaClient.generate(anyString(), anyString(), anyString(), any(byte[].class), anyInt()))
                    .willReturn("```\nSome extracted text\nLine 2\n```");

            OcrEngineResult result = engine.process(new byte[]{1}, "image/jpeg", ctx);
            assertThat(result.text()).isEqualTo("Some extracted text\nLine 2");
        }

        @Test
        @DisplayName("Reads config from EngineContext")
        void readsConfigFromContext() {
            given(ollamaClient.generate(eq("http://custom:1234"), eq("custom-model"),
                    anyString(), any(byte[].class), eq(30)))
                    .willReturn("text");

            EngineContext custom = ctx.withEngineConfig(Map.of(
                    "url", "http://custom:1234", "model", "custom-model", "timeout", "30"));

            engine.process(new byte[]{1}, "image/jpeg", custom);

            verify(ollamaClient).generate(eq("http://custom:1234"), eq("custom-model"),
                    anyString(), any(byte[].class), eq(30));
        }
    }

    @Nested
    @DisplayName("testConnection()")
    class TestConnection {

        @Test
        @DisplayName("Delegates to OllamaClient")
        void delegatesToClient() {
            given(ollamaClient.testConnection("http://localhost:11434", "glm-ocr"))
                    .willReturn(ConnectionTestResult.ok("Connected", 100));

            ConnectionTestResult result = engine.testConnection(Map.of(
                    "url", "http://localhost:11434", "model", "glm-ocr"));

            assertThat(result.success()).isTrue();
            assertThat(result.message()).contains("Connected");
        }
    }
}
