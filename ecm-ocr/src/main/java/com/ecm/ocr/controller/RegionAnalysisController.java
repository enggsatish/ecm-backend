package com.ecm.ocr.controller;

import com.ecm.ocr.engine.*;
import com.ecm.ocr.pipeline.GlmTrainingService;
import com.ecm.ocr.pipeline.PipelineConfig;
import com.ecm.ocr.service.MinioFetchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.*;

/**
 * Visual OCR training — select a region on a document and analyze/label it.
 *
 * <h3>Endpoints:</h3>
 * <ul>
 *   <li>POST /api/ocr/analyze-region — crop a region from a document page, send to GLM-OCR</li>
 *   <li>POST /api/ocr/training-examples — save a labeled region as a training example</li>
 *   <li>GET  /api/ocr/training-examples — list saved examples</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/ocr")
@RequiredArgsConstructor
public class RegionAnalysisController {

    private final MinioFetchService minioFetch;
    private final GlmOcrEngine glmOcrEngine;
    private final OllamaClient ollamaClient;
    private final GlmTrainingService trainingService;
    private final PipelineConfig pipelineConfig;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    /**
     * Analyze a selected region on a document page.
     *
     * <pre>
     * POST /api/ocr/analyze-region
     * {
     *   "documentId": "uuid",
     *   "page": 1,
     *   "region": { "x": 0.12, "y": 0.34, "width": 0.28, "height": 0.06 },
     *   "action": "READ" | "IDENTIFY"
     * }
     * </pre>
     *
     * Region coordinates are normalized (0.0 to 1.0) relative to the page dimensions.
     */
    @PostMapping("/analyze-region")
    public ResponseEntity<Map<String, Object>> analyzeRegion(@RequestBody RegionRequest request) {
        log.info("Region analysis: documentId={}, page={}, action={}, region={}",
                request.documentId, request.page, request.action, request.region);

        try {
            // 1. Fetch document from MinIO
            String storageKey = resolveStorageKey(request.documentId);
            String bucket = resolveBucket(request.documentId);
            byte[] docBytes = minioFetch.fetchBytes(bucket, storageKey);
            String contentType = resolveContentType(request.documentId);

            // 2. Get the page image
            byte[] pageImage = renderPage(docBytes, contentType, request.page);
            if (pageImage == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "Could not render page"));
            }

            // 3. Crop to selected region
            byte[] croppedImage = cropRegion(pageImage, request.region);
            if (croppedImage == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "Could not crop region"));
            }

            // 4. Send to GLM-OCR with appropriate prompt
            String prompt = buildRegionPrompt(request.action);

            // Get Ollama config from pipeline
            List<PipelineConfig.EngineEntry> engines = pipelineConfig.loadAllEngines();
            String ollamaUrl = "http://localhost:11434";
            String model = "glm-ocr";
            for (PipelineConfig.EngineEntry e : engines) {
                if ("glm-ocr".equals(e.engine())) {
                    ollamaUrl = e.config().getOrDefault("url", ollamaUrl);
                    model = e.config().getOrDefault("model", model);
                    break;
                }
            }

            String response = ollamaClient.generate(ollamaUrl, model, prompt, croppedImage, 60);

            if (response == null || response.isBlank()) {
                return ResponseEntity.ok(Map.of("text", "", "message", "No text detected in region"));
            }

            // 5. Parse response
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("text", cleanResponse(response));
            result.put("action", request.action);
            result.put("region", request.region);

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Region analysis failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Analysis failed: " + e.getMessage()));
        }
    }

    /**
     * Save a labeled region as a training example.
     */
    @PostMapping("/training-examples")
    public ResponseEntity<Map<String, Object>> saveTrainingExample(@RequestBody TrainingExampleRequest request) {
        // Resolve category code from ID
        String categoryCode = resolveCategoryCodeFromId(request.categoryId);
        log.info("Saving training example: categoryId={}, code={}, field={}, value={}",
                request.categoryId, categoryCode, request.fieldName, request.confirmedValue);

        try {
            Map<String, Object> expectedOutput = new LinkedHashMap<>();
            expectedOutput.put("fields", Map.of(request.fieldName, request.confirmedValue));
            if (categoryCode != null) {
                expectedOutput.put("category", categoryCode);
            }

            String outputJson = objectMapper.writeValueAsString(expectedOutput);

            jdbc.update(
                    "INSERT INTO ecm_admin.glm_ocr_examples " +
                    "(category_code, source, expected_output, confidence, created_by) " +
                    "VALUES (?, 'REGION', ?::jsonb, 100, ?)",
                    categoryCode != null ? categoryCode : "UNKNOWN",
                    outputJson,
                    "admin:visual-training");

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Training example saved: " + request.fieldName + " = " + request.confirmedValue));

        } catch (Exception e) {
            log.error("Failed to save training example: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Save failed: " + e.getMessage()));
        }
    }

    /**
     * List saved training examples. Filter by category code or categoryId.
     */
    @GetMapping("/training-examples")
    public ResponseEntity<List<Map<String, Object>>> listTrainingExamples(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Integer categoryId) {
        try {
            // Resolve categoryId to code if provided
            String catCode = category;
            if (catCode == null && categoryId != null) {
                catCode = resolveCategoryCodeFromId(categoryId);
            }

            List<Map<String, Object>> rows;
            if (catCode != null) {
                rows = jdbc.queryForList(
                        "SELECT id, category_code, source, expected_output, confidence, created_at, created_by " +
                        "FROM ecm_admin.glm_ocr_examples WHERE category_code = ? ORDER BY created_at DESC LIMIT 50",
                        catCode);
            } else {
                rows = jdbc.queryForList(
                        "SELECT id, category_code, source, expected_output, confidence, created_at, created_by " +
                        "FROM ecm_admin.glm_ocr_examples ORDER BY created_at DESC LIMIT 50");
            }
            return ResponseEntity.ok(rows);
        } catch (Exception e) {
            return ResponseEntity.ok(List.of());
        }
    }

    /**
     * Delete a training example by ID.
     */
    @DeleteMapping("/training-examples/{id}")
    public ResponseEntity<Map<String, Object>> deleteTrainingExample(@PathVariable long id) {
        try {
            int rows = jdbc.update("DELETE FROM ecm_admin.glm_ocr_examples WHERE id = ?", id);
            log.info("Deleted training example: id={}, rows={}", id, rows);
            return ResponseEntity.ok(Map.of("success", true, "deleted", rows));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("success", false, "error", e.getMessage()));
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private byte[] renderPage(byte[] docBytes, String contentType, int pageNumber) throws Exception {
        String bare = contentType != null ? contentType.toLowerCase().split(";")[0].trim() : "";

        if (bare.startsWith("image/")) {
            return docBytes; // already an image
        }

        if ("application/pdf".equals(bare)) {
            try (PDDocument doc = Loader.loadPDF(docBytes)) {
                int pageIndex = Math.max(0, pageNumber - 1);
                if (pageIndex >= doc.getNumberOfPages()) return null;

                PDFRenderer renderer = new PDFRenderer(doc);
                BufferedImage image = renderer.renderImageWithDPI(pageIndex, 300, ImageType.RGB);

                try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                    ImageIO.write(image, "png", baos);
                    return baos.toByteArray();
                }
            }
        }

        return null; // unsupported type
    }

    private byte[] cropRegion(byte[] imageBytes, RegionCoords region) throws Exception {
        BufferedImage fullImage = ImageIO.read(new java.io.ByteArrayInputStream(imageBytes));
        if (fullImage == null) return null;

        int imgW = fullImage.getWidth();
        int imgH = fullImage.getHeight();

        // Convert normalized coordinates (0.0-1.0) to pixels
        int x = (int) (region.x * imgW);
        int y = (int) (region.y * imgH);
        int w = (int) (region.width * imgW);
        int h = (int) (region.height * imgH);

        // Clamp to image bounds
        x = Math.max(0, Math.min(x, imgW - 1));
        y = Math.max(0, Math.min(y, imgH - 1));
        w = Math.max(1, Math.min(w, imgW - x));
        h = Math.max(1, Math.min(h, imgH - y));

        BufferedImage cropped = fullImage.getSubimage(x, y, w, h);

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(cropped, "png", baos);
            return baos.toByteArray();
        }
    }

    private String buildRegionPrompt(String action) {
        if ("IDENTIFY".equals(action)) {
            return "What type of content is in this image? Is it a signature, handwritten text, " +
                   "printed text, stamp, table, barcode, photo, checkbox, or logo? " +
                   "Also read any text visible. Respond with plain text, no JSON.";
        }
        // READ (default)
        return "Read and extract all text visible in this image. " +
               "Return ONLY the extracted text, nothing else. No explanation, no JSON.";
    }

    private String cleanResponse(String response) {
        if (response == null) return "";
        // Strip markdown formatting if present
        String clean = response.strip();
        if (clean.startsWith("```")) {
            int nl = clean.indexOf('\n');
            int end = clean.lastIndexOf("```");
            if (nl > 0 && end > nl) {
                clean = clean.substring(nl + 1, end).strip();
            }
        }
        return clean;
    }

    private String resolveCategoryCodeFromId(Integer categoryId) {
        if (categoryId == null) return null;
        try {
            return jdbc.queryForObject(
                    "SELECT code FROM ecm_admin.document_categories WHERE id = ?",
                    String.class, categoryId);
        } catch (Exception e) {
            log.debug("Could not resolve category code for id={}: {}", categoryId, e.getMessage());
            return null;
        }
    }

    private String resolveStorageKey(UUID documentId) {
        return jdbc.queryForObject(
                "SELECT storage_key FROM ecm_core.documents WHERE id = ?",
                String.class, documentId);
    }

    private String resolveBucket(UUID documentId) {
        try {
            return jdbc.queryForObject(
                    "SELECT storage_bucket FROM ecm_core.documents WHERE id = ?",
                    String.class, documentId);
        } catch (Exception e) {
            return "ecm-documents";
        }
    }

    private String resolveContentType(UUID documentId) {
        return jdbc.queryForObject(
                "SELECT mime_type FROM ecm_core.documents WHERE id = ?",
                String.class, documentId);
    }

    // ── Request DTOs ────────────────────────────────────────────────────────────

    record RegionRequest(UUID documentId, int page, RegionCoords region, String action) {}
    record RegionCoords(double x, double y, double width, double height) {}
    record TrainingExampleRequest(UUID documentId, Integer categoryId, String fieldName,
                                   String confirmedValue, int page, RegionCoords region) {}
}
