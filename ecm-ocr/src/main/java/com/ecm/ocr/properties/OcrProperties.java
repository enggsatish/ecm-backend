package com.ecm.ocr.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Bound from {@code ecm.ocr.*} in application.yml.
 *
 * <h3>Engine configuration:</h3>
 * <p>The OCR pipeline is now dynamically configured via Admin UI (tenant_config DB).
 * application.yml only provides fallback defaults for fresh installs.</p>
 *
 * <h3>Available engines:</h3>
 * <ul>
 *   <li><b>GLM-OCR</b> — Ollama vision model (OCR + classify + extract fields). Local, free.</li>
 *   <li><b>Azure AI</b> — Azure Document Intelligence (OCR + classify + extract). Cloud, paid.</li>
 *   <li><b>RapidOCR</b> — Local OCR container (text extraction only). Local, free.</li>
 * </ul>
 *
 * <h3>PDF handling:</h3>
 * <p>Scanned PDFs are detected by Tika embedded text check. If text &lt; threshold,
 * PDFBox renders each page to PNG for the OCR engine to process.</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "ecm.ocr")
public class OcrProperties {

    // ── General ───────────────────────────────────────────────────────────────

    /**
     * Max file size in bytes. Files exceeding this skip OCR entirely.
     * Default: 50 MB.
     */
    private long maxFileSizeBytes = 52_428_800L;

    // ── Scanned PDF Detection ─────────────────────────────────────────────────

    /**
     * Minimum chars Tika must extract from a PDF before treating it as native text.
     * Below this threshold → rendered page-by-page for OCR.
     * Default: 50.
     */
    private int scannedPdfTextThreshold = 50;

    /**
     * Maximum pages to render from a scanned PDF. Safety guard.
     * Default: 100.
     */
    private int scannedPdfMaxPages = 100;

    /**
     * DPI for rendering scanned PDF pages. 300 = recommended for accuracy.
     * Default: 300.
     */
    private int scannedPdfRenderDpi = 300;

    // ── RapidOCR Container (kept for backwards compatibility) ─────────────────

    /**
     * Whether the RapidOCR container client is available.
     * When false, the RapidOCR plugin will be non-functional.
     */
    private boolean rapidocrEnabled = true;

    /**
     * Base URL of the RapidOCR Docker container.
     */
    private String rapidocrUrl = "http://localhost:8884";

    /**
     * API endpoint path on the RapidOCR container.
     */
    private String rapidocrApiPath = "/ocr";

    /**
     * Multipart form field name the RapidOCR server expects.
     */
    private String rapidocrFileField = "image_file";

    /**
     * HTTP read timeout for RapidOCR requests (seconds).
     */
    private int rapidocrTimeoutSeconds = 60;

    // ── Ollama Defaults (overridden by tenant_config pipeline JSON) ───────────

    /**
     * Default Ollama base URL. Used when no pipeline config exists in DB.
     */
    private String ollamaUrl = "http://localhost:11434";

    /**
     * Default Ollama model name.
     */
    private String ollamaModel = "glm-ocr";

    /**
     * Default Ollama request timeout (seconds).
     * GLM-OCR 0.9B on 16GB Mac ≈ 5-15s per page. 120s allows for slow first load.
     */
    private int ollamaTimeoutSeconds = 120;

    // ── Tika (kept for Office doc text extraction) ────────────────────────────

    /**
     * Tika language setting for any fallback text extraction.
     */
    private String tikaLanguages = "eng";
}
