package com.ecm.ocr.engine;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Unified result returned by any OCR engine plugin.
 *
 * <p>Not all engines populate every field:
 * <ul>
 *   <li>RapidOCR: text only — category, fields, regions are null/empty</li>
 *   <li>Azure: text + fields — regions are empty</li>
 *   <li>GLM-OCR: text + fields + category + regions</li>
 * </ul>
 *
 * @param text              full extracted text
 * @param fields            structured fields (e.g. full_name, date_of_birth)
 * @param detectedCategory  category code if engine classified (e.g. "IDENTITY"), null otherwise
 * @param confidence        classification confidence 0-100, null if not classified
 * @param regions           bounding box regions with labels (GLM-OCR), empty list otherwise
 * @param engineId          which engine produced this result
 * @param modelUsed         specific model/variant used (e.g. "glm-ocr", "prebuilt-idDocument")
 */
public record OcrEngineResult(
        String text,
        Map<String, Object> fields,
        String detectedCategory,
        BigDecimal confidence,
        List<Region> regions,
        String engineId,
        String modelUsed
) {
    public static OcrEngineResult empty(String engineId) {
        return new OcrEngineResult("", Map.of(), null, null, List.of(), engineId, null);
    }

    public static OcrEngineResult textOnly(String text, String engineId) {
        return new OcrEngineResult(text, Map.of(), null, null, List.of(), engineId, null);
    }

    /**
     * A labelled region detected in the document with bounding box coordinates.
     *
     * @param label   region type: "title", "text", "table", "figure", "formula", "seal", etc.
     * @param content text content of this region
     * @param bbox    bounding box [x1, y1, x2, y2] normalized to 0-1000 scale
     */
    public record Region(String label, String content, int[] bbox) {}
}
