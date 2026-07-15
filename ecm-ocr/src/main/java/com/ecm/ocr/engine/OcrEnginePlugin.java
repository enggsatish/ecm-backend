package com.ecm.ocr.engine;

import java.util.Map;
import java.util.Set;

/**
 * Dynamic OCR engine plugin interface.
 *
 * <p>Each engine (GLM-OCR, Azure, RapidOCR) implements this interface.
 * The pipeline orchestrator calls engines in admin-configured order,
 * passing context between them so later engines can build on earlier results.</p>
 *
 * <p>Capabilities determine what the pipeline expects from each engine:
 * <ul>
 *   <li>{@code OCR} — can extract raw text from images/documents</li>
 *   <li>{@code CLASSIFY} — can identify document category</li>
 *   <li>{@code EXTRACT_FIELDS} — can extract structured fields (name, DOB, etc.)</li>
 * </ul>
 */
public interface OcrEnginePlugin {

    /** Unique engine identifier stored in pipeline config (e.g. "glm-ocr", "azure", "rapidocr"). */
    String engineId();

    /** Human-readable display name for admin UI. */
    String displayName();

    /** What this engine can do — determines how the pipeline uses its results. */
    Set<Capability> capabilities();

    /**
     * Process a document image/page.
     *
     * @param imageBytes  raw image bytes (PNG, JPEG, TIFF) or PDF bytes
     * @param contentType MIME type
     * @param context     pipeline context — category hint, text from prior engines, few-shot examples
     * @return structured result with text, fields, classification, regions
     */
    OcrEngineResult process(byte[] imageBytes, String contentType, EngineContext context);

    /**
     * Test connectivity and configuration.
     *
     * @param config engine-specific config map (url, model, key, etc.)
     * @return test result with success/failure and message
     */
    ConnectionTestResult testConnection(Map<String, String> config);

    enum Capability {
        OCR,
        CLASSIFY,
        EXTRACT_FIELDS
    }
}
