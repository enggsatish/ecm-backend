package com.ecm.ocr.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for EngineContext — verifies context propagation between pipeline engines.
 */
class EngineContextTest {

    @Test
    @DisplayName("initial() creates context with empty defaults")
    void initialContext() {
        var ctx = EngineContext.initial(UUID.randomUUID(), "doc.pdf", "IDENTITY", 3);

        assertThat(ctx.categoryCode()).isEqualTo("IDENTITY");
        assertThat(ctx.categoryId()).isEqualTo(3);
        assertThat(ctx.previousText()).isEmpty();
        assertThat(ctx.previousFields()).isEmpty();
        assertThat(ctx.fewShotExamples()).isEmpty();
        assertThat(ctx.engineConfig()).isEmpty();
    }

    @Test
    @DisplayName("withPreviousResult() carries forward text and fields from engine result")
    void withPreviousResult() {
        var ctx = EngineContext.initial(UUID.randomUUID(), "doc.pdf", null, null);

        var result = new OcrEngineResult("extracted text", Map.of("name", "John"),
                "IDENTITY", BigDecimal.valueOf(85), List.of(), "glm-ocr", "glm-ocr");

        var updated = ctx.withPreviousResult(result);

        assertThat(updated.previousText()).isEqualTo("extracted text");
        assertThat(updated.previousFields()).containsEntry("name", "John");
        assertThat(updated.categoryCode()).isEqualTo("IDENTITY");
    }

    @Test
    @DisplayName("withPreviousResult() keeps longer text")
    void keepsLongerText() {
        var ctx = EngineContext.initial(UUID.randomUUID(), "doc.pdf", null, null)
                .withPreviousResult(OcrEngineResult.textOnly("short", "engine1"));

        var longerResult = OcrEngineResult.textOnly("this is much longer text from a better engine", "engine2");
        var updated = ctx.withPreviousResult(longerResult);

        assertThat(updated.previousText()).isEqualTo("this is much longer text from a better engine");
    }

    @Test
    @DisplayName("withPreviousResult() does not overwrite longer text with shorter")
    void doesNotOverwriteWithShorter() {
        var ctx = EngineContext.initial(UUID.randomUUID(), "doc.pdf", null, null)
                .withPreviousResult(OcrEngineResult.textOnly("this is the longer original text from first engine", "engine1"));

        var shorterResult = OcrEngineResult.textOnly("short", "engine2");
        var updated = ctx.withPreviousResult(shorterResult);

        assertThat(updated.previousText()).contains("longer original text");
    }

    @Test
    @DisplayName("withPreviousResult() merges fields from multiple engines")
    void mergesFields() {
        var ctx = EngineContext.initial(UUID.randomUUID(), "doc.pdf", null, null)
                .withPreviousResult(new OcrEngineResult("text", Map.of("name", "John"),
                        null, null, List.of(), "e1", "e1"));

        var result2 = new OcrEngineResult("text", Map.of("name", "Jane", "dob", "1990"),
                null, null, List.of(), "e2", "e2");
        var updated = ctx.withPreviousResult(result2);

        // Second engine's fields merge with first (putAll overwrites)
        assertThat(updated.previousFields()).containsEntry("name", "Jane");
        assertThat(updated.previousFields()).containsEntry("dob", "1990");
    }

    @Test
    @DisplayName("withEngineConfig() sets engine-specific config")
    void withEngineConfig() {
        var ctx = EngineContext.initial(UUID.randomUUID(), "doc.pdf", null, null)
                .withEngineConfig(Map.of("url", "http://custom:1234", "model", "test-model"));

        assertThat(ctx.engineConfig()).containsEntry("url", "http://custom:1234");
        assertThat(ctx.engineConfig()).containsEntry("model", "test-model");
    }

    @Test
    @DisplayName("withExamples() adds few-shot examples")
    void withExamples() {
        var examples = List.of(
                new EngineContext.FewShotExample("IDENTITY", "{\"fields\":{\"name\":\"John\"}}"));

        var ctx = EngineContext.initial(UUID.randomUUID(), "doc.pdf", null, null)
                .withExamples(examples);

        assertThat(ctx.fewShotExamples()).hasSize(1);
        assertThat(ctx.fewShotExamples().get(0).categoryCode()).isEqualTo("IDENTITY");
    }
}
