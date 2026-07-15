package com.ecm.ocr.engine;

import com.ecm.ocr.engine.EngineContext.FewShotExample;
import com.ecm.ocr.pipeline.ExtractionTemplateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for GlmPromptBuilder — verifies prompt construction for different scenarios.
 */
@ExtendWith(MockitoExtension.class)
class GlmPromptBuilderTest {

    @Mock ExtractionTemplateService extractionTemplateService;
    GlmPromptBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new GlmPromptBuilder(extractionTemplateService);
    }

    @Test
    @DisplayName("Image prompt for unknown category includes all category names")
    void imagePromptUnknownCategory() {
        var ctx = EngineContext.initial(UUID.randomUUID(), "doc.jpg", null, null);
        String prompt = builder.buildImagePrompt(ctx);

        assertThat(prompt).contains("Classify the document into ONE of these categories");
        assertThat(prompt).contains("IDENTITY");
        assertThat(prompt).contains("INVOICE");
        assertThat(prompt).contains("MORTGAGE");
        assertThat(prompt).contains("no markdown fences");
    }

    @Test
    @DisplayName("Image prompt for known category includes specific field names")
    void imagePromptKnownCategory() {
        var ctx = EngineContext.initial(UUID.randomUUID(), "dl.jpg", "IDENTITY", 3);
        String prompt = builder.buildImagePrompt(ctx);

        assertThat(prompt).contains("IDENTITY document");
        assertThat(prompt).contains("full_name");
        assertThat(prompt).contains("date_of_birth");
        assertThat(prompt).contains("document_number");
        assertThat(prompt).doesNotContain("Classify the document");
    }

    @Test
    @DisplayName("Text prompt includes OCR text from context")
    void textPromptIncludesOcrText() {
        var ctx = EngineContext.initial(UUID.randomUUID(), "doc.jpg", null, null)
                .withPreviousResult(OcrEngineResult.textOnly(
                        "ALBERTA GOVERNMENT ERIN ANDERSON OPERATOR'S LICENCE", "glm-ocr"));

        String prompt = builder.buildTextPrompt(ctx);

        assertThat(prompt).contains("ALBERTA GOVERNMENT");
        assertThat(prompt).contains("--- OCR TEXT ---");
    }

    @Test
    @DisplayName("Text prompt truncates long OCR text")
    void textPromptTruncatesLongText() {
        String longText = "A".repeat(10000);
        var ctx = EngineContext.initial(UUID.randomUUID(), "doc.jpg", null, null)
                .withPreviousResult(OcrEngineResult.textOnly(longText, "glm-ocr"));

        String prompt = builder.buildTextPrompt(ctx);

        assertThat(prompt).contains("[... truncated ...]");
        assertThat(prompt.length()).isLessThan(10000);
    }

    @Test
    @DisplayName("Few-shot examples are included in prompt")
    void fewShotExamplesIncluded() {
        var examples = List.of(
                new FewShotExample("IDENTITY", "{\"fields\":{\"full_name\":\"John\"}}"));

        var ctx = EngineContext.initial(UUID.randomUUID(), "doc.jpg", "IDENTITY", 3)
                .withExamples(examples);

        String prompt = builder.buildImagePrompt(ctx);

        assertThat(prompt).contains("Example 1");
        assertThat(prompt).contains("full_name");
        assertThat(prompt).contains("John");
    }

    @Test
    @DisplayName("Prompt uses concrete example format — not 'field_name': 'value' template")
    void usesConcreteExampleNotTemplate() {
        var ctx = EngineContext.initial(UUID.randomUUID(), "doc.jpg", null, null);
        String prompt = builder.buildImagePrompt(ctx);

        // Should NOT contain template placeholders that models echo back
        assertThat(prompt).doesNotContain("\"field_name\"");
        assertThat(prompt).doesNotContain("\"value\"");
        // Should contain concrete example with multiple given/middle names
        assertThat(prompt).contains("Mary Ann Jane Smith");
        assertThat(prompt).contains("middle_name");
    }
}
