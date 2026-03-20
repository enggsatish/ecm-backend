package com.ecm.ocr.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Bound from {@code ecm.ocr.*} in application.yml.
 *
 * ── Tesseract modes ───────────────────────────────────────────────────────────
 *
 *   MODE A — Remote Docker container (recommended for local dev + production):
 *     tesseract-server-enabled: true
 *     tesseract-server-url:     http://localhost:8884
 *     tesseract-enabled:        true   ← still gates whether OCR runs at all
 *
 *   MODE B — Local binary (legacy / CI only):
 *     tesseract-server-enabled: false
 *     tesseract-enabled:        true
 *     (Tika calls the 'tesseract' binary on $PATH)
 *
 *   MODE C — No OCR (text-only environment):
 *     tesseract-enabled:        false
 *     (Tika extracts embedded text only; no Tesseract invoked at all)
 *
 * ── Content routing when tesseract-server-enabled=true ───────────────────────
 *
 *   image/*          → bytes sent directly to Tesseract HTTP container
 *   application/pdf  → Tika extracts embedded text first
 *                      if chars < scanned-pdf-text-threshold
 *                        → PDFBox renders each page to PNG
 *                        → each page sent to Tesseract HTTP container
 *                        → per-page results concatenated
 *   other (Office,
 *   text/*, etc.)    → Tika only (embedded text extraction)
 */
@Data
@Component
@ConfigurationProperties(prefix = "ecm.ocr")
public class OcrProperties {

    // ── General ───────────────────────────────────────────────────────────────

    /**
     * Max file size in bytes. Files exceeding this skip OCR entirely
     * and are indexed by metadata only. Prevents OOM on large scanned PDFs.
     * Default: 50 MB.
     */
    private long maxFileSizeBytes = 52_428_800L;

    /**
     * Master OCR switch. When false, Tesseract is never invoked regardless of
     * other settings. Only native embedded text (PDFs, Office docs) is extracted.
     */
    private boolean tesseractEnabled = true;

    /**
     * ISO 639-3 language pack codes, comma-separated.
     * Passed to Tesseract for language-specific character recognition.
     * Examples: "eng", "eng+fra", "eng+deu".
     */
    private String tesseractLanguages = "eng";

    // ── Remote Tesseract HTTP Server ──────────────────────────────────────────

    /**
     * When true, image files and scanned PDF pages are sent to the Tesseract
     * Docker HTTP container instead of calling the local {@code tesseract} binary
     * via Tika's subprocess path.
     *
     * Both {@code tesseract-enabled} AND this flag must be true for the HTTP
     * path to activate.
     */
    private boolean tesseractServerEnabled = false;

    /**
     * Base URL of the Tesseract HTTP Docker container.
     * Example: http://localhost:8884
     * Do NOT include the API path here — set that via {@code tesseractServerApiPath}.
     */
    private String tesseractServerUrl = "http://localhost:8884";

    /**
     * API endpoint path on the Tesseract container.
     * Common values:
     *   "/"           → tesseract-server (npm), simpletesseract
     *   "/tesseract"  → python-tesseract-api-server
     *   "/api/ocr"    → custom wrappers
     * Default "/" works with most containerised Tesseract HTTP servers.
     */
    private String tesseractServerApiPath = "/";

    /**
     * Multipart form field name the server expects for the image bytes.
     * Most servers use "file". Some use "image" or "upload".
     */
    private String tesseractServerFileField = "file";

    /**
     * HTTP read timeout in seconds for requests to the Tesseract container.
     * High-resolution TIFFs can take 20–40 s. Default: 60 s.
     */
    private int tesseractServerTimeoutSeconds = 60;

    // ── Scanned PDF Detection ─────────────────────────────────────────────────

    /**
     * Minimum number of non-whitespace characters Tika must extract from a PDF
     * before it is considered a native (text-layer) PDF.
     *
     * If Tika extracts fewer than this many characters from a PDF, the file is
     * treated as a scanned PDF: PDFBox renders each page to a PNG image and each
     * image is sent to the Tesseract HTTP container for OCR.
     *
     * Rationale: scanned PDFs often contain zero embedded text, but some have a
     * small amount from partial text recognition or metadata — a threshold slightly
     * above zero avoids re-processing PDFs that already have good text coverage.
     *
     * Default: 50 characters.
     * Raise to 200–500 if partial-text PDFs (fillable forms) are being re-OCR'd
     * unnecessarily.
     */
    private int scannedPdfTextThreshold = 50;

    /**
     * Maximum number of pages to OCR in a single scanned PDF.
     * Pages beyond this limit are skipped; a truncation notice is appended to
     * the extracted text.
     *
     * This is a safety guard against extremely large scanned documents (e.g. a
     * 500-page archival scan) consuming excessive memory or blocking the queue.
     * Each page is rendered at 300 DPI — a typical A4 page produces a ~3 MB PNG.
     *
     * Default: 100 pages (~300 MB peak heap for the worst case).
     * Lower to 50 if the service is memory-constrained.
     */
    private int scannedPdfMaxPages = 100;

    /**
     * DPI (dots per inch) used when rendering scanned PDF pages to images.
     * 300 DPI is the Tesseract-recommended minimum for accurate recognition.
     * Lowering to 150 halves memory usage at the cost of recognition accuracy.
     * Default: 300.
     */
    private int scannedPdfRenderDpi = 300;
}
