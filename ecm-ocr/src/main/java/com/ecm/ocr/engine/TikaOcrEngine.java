package com.ecm.ocr.engine;

import com.ecm.ocr.properties.OcrProperties;
import com.ecm.ocr.service.TesseractHttpClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.ocr.TesseractOCRConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;
import org.xml.sax.SAXException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

/**
 * Tika 3.x OCR engine with PDFBox-based scanned PDF support and remote
 * Tesseract HTTP container routing.
 *
 * ── Content routing matrix ───────────────────────────────────────────────────
 *
 *  Content type        | tesseractEnabled | tesseractServerEnabled | Path taken
 *  --------------------|------------------|------------------------|-----------------------------
 *  image/*             | true             | true                   | → Tesseract HTTP (direct)
 *  image/*             | true             | false                  | → Tika (local binary)
 *  application/pdf     | true             | true                   | → Tika (embedded text check)
 *                      |                  |                        |   if chars < threshold
 *                      |                  |                        |   → PDFBox render + Tesseract HTTP (per page)
 *  application/pdf     | true             | false                  | → Tika (embedded text + local binary OCR)
 *  application/pdf     | false            | any                    | → Tika (embedded text only, skipOcr=true)
 *  Office / text / *   | any              | any                    | → Tika (embedded text only, skipOcr=true)
 *
 * ── Scanned PDF detection ─────────────────────────────────────────────────────
 * A PDF is considered "scanned" when Tika extracts fewer than
 * {@code ecm.ocr.scanned-pdf-text-threshold} characters from it (default: 50).
 * For scanned PDFs, each page is:
 *   1. Rendered to a BufferedImage at {@code scanned-pdf-render-dpi} (default 300 DPI)
 *   2. Written to PNG bytes in memory
 *   3. POST'd to the Tesseract HTTP container
 *   4. Per-page results concatenated with page markers
 *
 * Pages beyond {@code scanned-pdf-max-pages} (default 100) are skipped to prevent
 * excessive memory usage; a truncation notice is appended to the extracted text.
 *
 * ── Tika 3.x API notes ────────────────────────────────────────────────────────
 *   setSkipOcr()       — camelCase in 3.x (reverted from 2.x's setSkipOCR)
 *   Loader.loadPDF()   — PDFBox 3.x replaces PDDocument.load() (old multi-arg
 *                        overloads with MemoryUsageSetting were removed in 3.x)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TikaOcrEngine implements OcrEngine {

    private final OcrProperties       props;
    private final TesseractHttpClient tesseractHttpClient;

    /**
     * Raw image MIME types routed directly to the Tesseract HTTP container.
     * "application/pdf" is intentionally absent — PDFs go through Tika first
     * for the embedded-text check.
     */
    private static final Set<String> IMAGE_CONTENT_TYPES = Set.of(
            "image/jpeg",
            "image/jpg",
            "image/png",
            "image/tiff",
            "image/tif",
            "image/bmp",
            "image/gif",
            "image/webp",
            "image/x-portable-bitmap",
            "image/x-portable-graymap",
            "image/x-portable-pixmap"
    );

    // ── OcrEngine interface ───────────────────────────────────────────────────

    @Override
    public String extract(InputStream inputStream,
                          String contentType) throws OcrException {
        return extract(inputStream, contentType, null);
    }

    /**
     * Main entry point — routes to Tesseract HTTP, Tika, or the scanned-PDF
     * path depending on content type and configuration.
     *
     * @param inputStream raw document bytes (may be a ByteArrayInputStream)
     * @param contentType MIME type of the document
     * @param documentId  UUID or identifier for log correlation (may be null)
     */
    @Override
    public String extract(InputStream inputStream,
                          String contentType,
                          Object documentId) throws OcrException {

        String bare = normalise(contentType);

        // ── PATH 1: Raw image → Tesseract HTTP ───────────────────────────────
        if (props.isTesseractEnabled()
                && props.isTesseractServerEnabled()
                && IMAGE_CONTENT_TYPES.contains(bare)) {

            log.debug("OCR path: image → Tesseract HTTP | type={} docId={}", contentType, documentId);
            try {
                byte[] bytes = inputStream.readAllBytes();
                return tesseractHttpClient.recognizeImage(bytes, contentType, documentId);
            } catch (IOException e) {
                throw new OcrException("Failed to read image stream: " + e.getMessage(), e);
            }
        }

        // ── PATH 2: PDF with Tesseract server enabled ────────────────────────
        //    Step A: extract embedded text via Tika (fast, always worth doing)
        //    Step B: if text < threshold → treat as scanned, do page-by-page OCR
        if ("application/pdf".equals(bare)
                && props.isTesseractEnabled()
                && props.isTesseractServerEnabled()) {

            log.debug("OCR path: PDF → embedded text check first | docId={}", documentId);
            try {
                byte[] pdfBytes = inputStream.readAllBytes();
                return extractPdf(pdfBytes, contentType, documentId);
            } catch (IOException e) {
                throw new OcrException("Failed to read PDF stream: " + e.getMessage(), e);
            }
        }

        // ── PATH 3: Everything else (Office, text/*, unknown) → Tika only ────
        log.debug("OCR path: Tika only | type={} docId={}", contentType, documentId);
        return extractWithTika(inputStream, contentType, documentId, bare);
    }

    // ── PDF extraction ────────────────────────────────────────────────────────

    /**
     * Two-pass PDF extraction:
     *   Pass 1 — Tika for embedded text (fast; handles native PDFs in milliseconds)
     *   Pass 2 — PDFBox render + Tesseract HTTP per page (only for scanned PDFs)
     */
    private String extractPdf(byte[] pdfBytes,
                              String contentType,
                              Object documentId) throws OcrException {

        // Pass 1: embedded text via Tika (setSkipOcr=true — no local binary needed)
        String embeddedText = extractWithTika(
                new ByteArrayInputStream(pdfBytes), contentType, documentId, "application/pdf");

        int charCount = embeddedText.strip().length();

        if (charCount >= props.getScannedPdfTextThreshold()) {
            log.info("PDF has embedded text ({} chars ≥ threshold {}), skipping page OCR | docId={}",
                    charCount, props.getScannedPdfTextThreshold(), documentId);
            return embeddedText;
        }

        // Pass 2: scanned PDF — render pages and send to Tesseract HTTP
        log.info("PDF appears scanned ({} chars < threshold {}), rendering pages for OCR | docId={}",
                charCount, props.getScannedPdfTextThreshold(), documentId);
        return ocrScannedPdfPages(pdfBytes, documentId);
    }

    /**
     * Renders each page of a scanned PDF to PNG and sends it to the Tesseract
     * HTTP container. Results are concatenated with page markers.
     *
     * Memory note: one page is rendered and sent at a time; the BufferedImage
     * is eligible for GC before the next page starts. Peak heap = ~1 page image
     * + its PNG encoding (typically 2–6 MB for A4 at 300 DPI).
     */
    private String ocrScannedPdfPages(byte[] pdfBytes,
                                      Object documentId) throws OcrException {
        // PDFBox 3.x: Loader.loadPDF() replaces PDDocument.load()
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {

            int totalPages   = doc.getNumberOfPages();
            int pagesToOcr   = Math.min(totalPages, props.getScannedPdfMaxPages());
            PDFRenderer renderer = new PDFRenderer(doc);
            StringBuilder sb = new StringBuilder();

            log.info("Scanned PDF: {} total pages, processing {} at {} DPI | docId={}",
                    totalPages, pagesToOcr, props.getScannedPdfRenderDpi(), documentId);

            for (int i = 0; i < pagesToOcr; i++) {
                int pageNum = i + 1;   // 1-based for log messages
                log.debug("Rendering page {}/{} | docId={}", pageNum, pagesToOcr, documentId);

                // ── Render page to RGB image ──────────────────────────────────
                // ImageType.RGB is sufficient for most scanned docs.
                // Use ImageType.GRAY for black-and-white archival scans to halve
                // memory (encode as PNG with 1 channel instead of 3).
                BufferedImage pageImage = renderer.renderImageWithDPI(
                        i, props.getScannedPdfRenderDpi(), ImageType.RGB);

                // ── Encode to PNG bytes ───────────────────────────────────────
                // PNG is lossless and widely supported by Tesseract containers.
                byte[] pngBytes;
                try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                    boolean written = ImageIO.write(pageImage, "png", baos);
                    if (!written) {
                        log.warn("ImageIO could not write page {} as PNG — skipping | docId={}",
                                pageNum, documentId);
                        continue;
                    }
                    pngBytes = baos.toByteArray();
                }

                // ── Allow GC on the BufferedImage before the HTTP call ────────
                // The image is no longer needed once encoded to bytes.
                //noinspection UnusedAssignment
                pageImage = null;

                log.debug("Page {} PNG: {} bytes | docId={}", pageNum, pngBytes.length, documentId);

                // ── Send to Tesseract HTTP container ──────────────────────────
                // documentId + page label for per-request log correlation
                String pageText = tesseractHttpClient.recognizeImage(
                        pngBytes, "image/png", documentId + "_p" + pageNum);

                if (!pageText.isBlank()) {
                    if (sb.length() > 0) {
                        sb.append("\n\n");
                    }
                    // Optional page marker — helps humans reading the extracted text
                    // and makes page-level field extraction possible in future.
                    if (pagesToOcr > 1) {
                        sb.append("=== Page ").append(pageNum).append(" ===\n");
                    }
                    sb.append(pageText.strip());
                }
            }

            // ── Append truncation notice if pages were skipped ────────────────
            if (totalPages > props.getScannedPdfMaxPages()) {
                sb.append("\n\n[OCR truncated: document has ")
                        .append(totalPages)
                        .append(" pages; only the first ")
                        .append(props.getScannedPdfMaxPages())
                        .append(" were processed. Increase ecm.ocr.scanned-pdf-max-pages to process more.]");
                log.warn("Scanned PDF truncated: {} total pages, {} processed | docId={}",
                        totalPages, props.getScannedPdfMaxPages(), documentId);
            }

            String result = sb.toString().strip();
            log.info("Scanned PDF OCR complete: {} pages processed, {} chars extracted | docId={}",
                    pagesToOcr, result.length(), documentId);
            return result;

        } catch (IOException e) {
            throw new OcrException("PDFBox rendering failed: " + e.getMessage(), e);
        }
    }

    // ── Tika extraction ───────────────────────────────────────────────────────

    /**
     * Extracts embedded text using Tika's AutoDetectParser.
     *
     * Tika's internal Tesseract subprocess call is always disabled here
     * ({@code setSkipOcr(true)}) because:
     *   a) There is no local Tesseract binary in the standard dev / prod environment
     *   b) OCR for images and scanned PDFs is handled by TesseractHttpClient
     *
     * Tika is still used for all non-image, non-PDF content types (Office docs,
     * plain text, HTML, email, etc.) and for native PDF text extraction.
     */
    private String extractWithTika(InputStream inputStream,
                                   String contentType,
                                   Object documentId,
                                   String normalizedType) throws OcrException {
        try {
            AutoDetectParser   parser   = new AutoDetectParser();
            BodyContentHandler handler  = new BodyContentHandler(-1);   // -1 = unlimited
            Metadata           metadata = new Metadata();

            TesseractOCRConfig tessConf = new TesseractOCRConfig();
            tessConf.setLanguage(props.getTesseractLanguages());
            tessConf.setTimeoutSeconds(120);

            // Always disable Tika's internal subprocess path.
            // OCR is handled externally via TesseractHttpClient.
            // Tika 3.x reverted 2.x's setSkipOCR (uppercase) back to setSkipOcr (camelCase).
            tessConf.setSkipOcr(true);

            ParseContext context = new ParseContext();
            context.set(TesseractOCRConfig.class, tessConf);
            context.set(Parser.class, parser);   // required for recursive parsing (zip, email)

            parser.parse(inputStream, handler, metadata, context);

            String text = handler.toString().strip();
            log.debug("Tika extracted {} chars | type={} docId={}", text.length(), contentType, documentId);
            return text;

        } catch (SAXException | IOException | TikaException e) {
            throw new OcrException("Tika extraction failed: " + e.getMessage(), e);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Lowercases and strips MIME parameters (e.g. "; charset=UTF-8"). */
    private static String normalise(String contentType) {
        if (contentType == null) return "";
        String lower = contentType.toLowerCase();
        int semi = lower.indexOf(';');
        return semi >= 0 ? lower.substring(0, semi).strip() : lower.strip();
    }
}
