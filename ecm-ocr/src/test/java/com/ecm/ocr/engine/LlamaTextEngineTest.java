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
 * Unit tests for LlamaTextEngine — verifies classification and field extraction
 * from OCR text using Llama 3.2 via Ollama.
 */
@ExtendWith(MockitoExtension.class)
class LlamaTextEngineTest {

    @Mock OllamaClient ollamaClient;
    @Mock ExtractionTemplateService extractionTemplateService;
    @Mock AiGatewayConfigService aiGatewayConfig;
    @Mock AiGatewayInvokeClient aiGatewayClient;
    ObjectMapper objectMapper = new ObjectMapper();

    GlmPromptBuilder promptBuilder;
    LlamaTextEngine engine;

    @BeforeEach
    void setUp() {
        promptBuilder = new GlmPromptBuilder(extractionTemplateService);
        engine = new LlamaTextEngine(ollamaClient, promptBuilder, objectMapper, aiGatewayConfig, aiGatewayClient);
    }

    @Test
    @DisplayName("Engine ID is 'llama-text'")
    void engineId() {
        assertThat(engine.engineId()).isEqualTo("llama-text");
    }

    @Test
    @DisplayName("Capabilities include CLASSIFY and EXTRACT_FIELDS but not OCR")
    void capabilities() {
        assertThat(engine.capabilities())
                .contains(OcrEnginePlugin.Capability.CLASSIFY, OcrEnginePlugin.Capability.EXTRACT_FIELDS);
        assertThat(engine.capabilities())
                .doesNotContain(OcrEnginePlugin.Capability.OCR);
    }

    @Nested
    @DisplayName("process()")
    class Process {

        EngineContext ctx(String text) {
            return EngineContext.initial(UUID.randomUUID(), "test.jpg", null, null)
                    .withEngineConfig(Map.of("url", "http://localhost:11434", "model", "llama3.2:3b", "timeout", "60"))
                    .withPreviousResult(OcrEngineResult.textOnly(text, "glm-ocr"));
        }

        @Test
        @DisplayName("Classifies document and extracts fields from valid JSON response")
        void classifiesAndExtracts() {
            String llamaResponse = """
                    {"category":"IDENTITY","confidence":90,"fields":{"full_name":"Erin Anderson","date_of_birth":"22 APR 1985","document_number":"999902-638"}}
                    """;

            given(ollamaClient.generateText(anyString(), anyString(), anyString(), anyInt()))
                    .willReturn(llamaResponse);

            OcrEngineResult result = engine.process(null, null,
                    ctx("ALBERTA GOVERNMENT ERIN ANDERSON OPERATOR'S LICENCE No: 999902-638"));

            assertThat(result.detectedCategory()).isEqualTo("IDENTITY");
            assertThat(result.confidence()).isNotNull();
            assertThat(result.confidence().doubleValue()).isEqualTo(90.0);
            assertThat(result.fields()).containsEntry("full_name", "Erin Anderson");
            assertThat(result.fields()).containsEntry("document_number", "999902-638");
            assertThat(result.text()).contains("ERIN ANDERSON");
        }

        @Test
        @DisplayName("Handles markdown-wrapped JSON response")
        void handlesMarkdownWrapped() {
            String llamaResponse = "```json\n{\"category\":\"INVOICE\",\"confidence\":80,\"fields\":{\"vendor_name\":\"Acme Corp\"}}\n```";

            given(ollamaClient.generateText(anyString(), anyString(), anyString(), anyInt()))
                    .willReturn(llamaResponse);

            OcrEngineResult result = engine.process(null, null,
                    ctx("Invoice #12345 Acme Corp Total Due: $500.00"));

            assertThat(result.detectedCategory()).isEqualTo("INVOICE");
            assertThat(result.fields()).containsEntry("vendor_name", "Acme Corp");
        }

        @Test
        @DisplayName("Returns empty when no previous text available")
        void emptyWhenNoText() {
            EngineContext emptyCtx = EngineContext.initial(UUID.randomUUID(), "test.jpg", null, null)
                    .withEngineConfig(Map.of("url", "http://localhost:11434", "model", "llama3.2:3b", "timeout", "60"));

            OcrEngineResult result = engine.process(null, null, emptyCtx);
            assertThat(result.text()).isEmpty();
        }

        @Test
        @DisplayName("Returns empty when Ollama returns empty")
        void emptyWhenOllamaEmpty() {
            given(ollamaClient.generateText(anyString(), anyString(), anyString(), anyInt()))
                    .willReturn("");

            OcrEngineResult result = engine.process(null, null,
                    ctx("Some document text here that is long enough"));
            assertThat(result.text()).isEmpty();
        }

        @Test
        @DisplayName("Filters out prompt example values from fields")
        void filtersExampleValues() {
            // Llama sometimes echoes the prompt example values
            String llamaResponse = "{\"category\":\"IDENTITY\",\"confidence\":85,\"fields\":{\"full_name\":\"Mary Jane Smith\",\"date_of_birth\":\"1990-01-15\",\"real_field\":\"actual value\"}}";

            given(ollamaClient.generateText(anyString(), anyString(), anyString(), anyInt()))
                    .willReturn(llamaResponse);

            OcrEngineResult result = engine.process(null, null,
                    ctx("Some document text that is definitely long enough for processing"));

            assertThat(result.fields()).doesNotContainKey("full_name"); // filtered — example value
            assertThat(result.fields()).doesNotContainKey("date_of_birth"); // filtered — example value
            assertThat(result.fields()).containsEntry("real_field", "actual value");
        }

        @Test
        @DisplayName("Handles malformed JSON gracefully")
        void handlesMalformedJson() {
            given(ollamaClient.generateText(anyString(), anyString(), anyString(), anyInt()))
                    .willReturn("I don't understand the request");

            OcrEngineResult result = engine.process(null, null,
                    ctx("Some document text here that is long enough"));
            assertThat(result.text()).isEmpty(); // empty, not crash
        }
    }
}
