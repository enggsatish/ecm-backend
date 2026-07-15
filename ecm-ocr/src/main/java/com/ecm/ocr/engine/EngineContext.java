package com.ecm.ocr.engine;

import java.util.List;
import java.util.Map;

/**
 * Context passed between engines in the dynamic pipeline.
 *
 * <p>Each engine receives context from previous engines so it can build
 * on their results. For example, RapidOCR extracts text first, then
 * GLM-OCR classifies and extracts fields from that text.</p>
 *
 * @param categoryCode   category hint from user or previous engine (null if unknown)
 * @param categoryId     category DB ID (null if unknown)
 * @param previousText   text extracted by a prior engine in the pipeline
 * @param previousFields fields extracted by a prior engine
 * @param fewShotExamples training examples for LLM-based engines
 * @param documentId     for log correlation
 * @param documentName   original filename
 */
public record EngineContext(
        String categoryCode,
        Integer categoryId,
        String previousText,
        Map<String, Object> previousFields,
        List<FewShotExample> fewShotExamples,
        Object documentId,
        String documentName,
        Map<String, String> engineConfig
) {
    public static EngineContext initial(Object documentId, String documentName,
                                        String categoryCode, Integer categoryId) {
        return new EngineContext(categoryCode, categoryId, "", Map.of(), List.of(),
                documentId, documentName, Map.of());
    }

    /** Create a new context carrying forward text and fields from a previous engine. */
    public EngineContext withPreviousResult(OcrEngineResult result) {
        String bestText = result.text() != null && result.text().length() > this.previousText.length()
                ? result.text() : this.previousText;
        Map<String, Object> mergedFields = new java.util.LinkedHashMap<>(this.previousFields);
        if (result.fields() != null) mergedFields.putAll(result.fields());

        String cat = result.detectedCategory() != null ? result.detectedCategory() : this.categoryCode;
        return new EngineContext(cat, this.categoryId, bestText, mergedFields,
                this.fewShotExamples, this.documentId, this.documentName, this.engineConfig);
    }

    /** Add few-shot examples to context. */
    public EngineContext withExamples(List<FewShotExample> examples) {
        return new EngineContext(categoryCode, categoryId, previousText, previousFields,
                examples, documentId, documentName, engineConfig);
    }

    /** Set engine-specific config for the current engine being called. */
    public EngineContext withEngineConfig(Map<String, String> config) {
        return new EngineContext(categoryCode, categoryId, previousText, previousFields,
                fewShotExamples, documentId, documentName, config != null ? config : Map.of());
    }

    /**
     * A few-shot training example for LLM-based engines.
     *
     * @param categoryCode the document category
     * @param expectedJson the expected JSON output for this example
     */
    public record FewShotExample(String categoryCode, String expectedJson) {}
}
