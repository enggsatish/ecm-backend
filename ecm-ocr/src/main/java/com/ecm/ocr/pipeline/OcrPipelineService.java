package com.ecm.ocr.pipeline;

import com.ecm.ocr.classification.CustomerMatcherService;
import com.ecm.ocr.classification.CustomerMatchResult;
import com.ecm.ocr.classification.DocumentClassifierService;
import com.ecm.ocr.engine.*;
import com.ecm.ocr.engine.EngineContext.FewShotExample;
import com.ecm.ocr.event.OcrCompletedEvent;
import com.ecm.ocr.event.OcrRequestMessage;
import com.ecm.ocr.properties.OcrProperties;
import com.ecm.ocr.service.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Orchestrates the dynamic OCR pipeline — runs engines in admin-configured order.
 *
 * <h3>Pipeline execution:</h3>
 * <ol>
 *   <li>Load enabled engines from tenant_config (ordered by priority)</li>
 *   <li>Prepare document (fetch bytes, render PDF pages if needed)</li>
 *   <li>Run each engine in order:
 *     <ul>
 *       <li>Text-only engines (RapidOCR): extract text, pass to next engine</li>
 *       <li>Full engines (GLM-OCR, Azure): classify + extract. Accept if confidence ≥ threshold</li>
 *       <li>Last engine: always accept (no further fallback)</li>
 *     </ul>
 *   </li>
 *   <li>Customer matching from best available fields</li>
 *   <li>Write back to DB, index, publish event</li>
 * </ol>
 *
 * <h3>Backwards compatible:</h3>
 * <p>If no pipeline config exists, defaults to RapidOCR → Azure (current behavior).</p>
 */
@Slf4j
@Service
public class OcrPipelineService {

    private final MinioFetchService        minioFetch;
    private final DocumentWritebackService writeback;
    private final DocumentIndexService     indexService;
    private final RabbitTemplate           rabbit;
    private final OcrProperties            props;
    private final JdbcTemplate             jdbc;
    private final CustomerMatcherService    customerMatcher;
    private final DocumentClassifierService documentClassifier;
    private final GlmTrainingService       trainingService;
    private final PipelineConfig           pipelineConfig;
    private final FieldNormalizerService    fieldNormalizer;

    // All registered engine plugins — injected by Spring, keyed by engineId
    private final Map<String, OcrEnginePlugin> engineRegistry;

    // Tika for embedded text extraction from Office docs / native PDFs
    private final TikaOcrEngine tikaEngine;

    private static final String COMPLETED_EXCHANGE = "ecm.ocr.completed";
    private static final int MIN_USEFUL_TEXT_LENGTH = 50;
    private static final String CATEGORY_CODE_SQL =
            "SELECT code FROM ecm_admin.document_categories WHERE id = ?";

    public OcrPipelineService(
            MinioFetchService minioFetch,
            DocumentWritebackService writeback,
            DocumentIndexService indexService,
            RabbitTemplate rabbit,
            OcrProperties props,
            JdbcTemplate jdbc,
            CustomerMatcherService customerMatcher,
            DocumentClassifierService documentClassifier,
            GlmTrainingService trainingService,
            PipelineConfig pipelineConfig,
            FieldNormalizerService fieldNormalizer,
            List<OcrEnginePlugin> plugins,
            TikaOcrEngine tikaEngine) {
        this.minioFetch = minioFetch;
        this.writeback = writeback;
        this.indexService = indexService;
        this.rabbit = rabbit;
        this.props = props;
        this.jdbc = jdbc;
        this.customerMatcher = customerMatcher;
        this.documentClassifier = documentClassifier;
        this.trainingService = trainingService;
        this.pipelineConfig = pipelineConfig;
        this.fieldNormalizer = fieldNormalizer;
        this.tikaEngine = tikaEngine;

        // Build engine registry from Spring-injected plugins
        this.engineRegistry = new LinkedHashMap<>();
        for (OcrEnginePlugin plugin : plugins) {
            engineRegistry.put(plugin.engineId(), plugin);
            log.info("Registered OCR engine plugin: {} ({})", plugin.engineId(), plugin.displayName());
        }
    }

    public void process(OcrRequestMessage msg) {
        log.info("OCR pipeline start: documentId={}, key={}, contentType={}",
                msg.documentId(), msg.storageKey(), msg.contentType());
        long start = System.currentTimeMillis();

        try {
            // 1. Fetch bytes from MinIO
            byte[] bytes = minioFetch.fetchBytes(msg.storageBucket(), msg.storageKey());

            var steps = new PipelineStep.Builder();

            // 2. Skip oversized files
            if (bytes.length > props.getMaxFileSizeBytes()) {
                log.warn("File too large for OCR: {} bytes, documentId={}", bytes.length, msg.documentId());
                steps.add(PipelineStep.skipped("OCR", "ocr", "Skipped", "file too large"));
                writeAndPublish(msg, "", Map.of(), null, null, null, null, steps.build(), start);
                return;
            }

            // 3. Resolve category if provided
            String catCode = resolveCategoryCode(msg.categoryId());

            // 4. Prepare image bytes for OCR engines
            // For PDFs: always render pages to PNG (vision models need images, not PDF bytes)
            // For images: use bytes directly
            // For Office docs: Tika extracts text, no images needed
            List<byte[]> pageImages = prepareImages(bytes, msg.contentType(), msg.documentId(), steps);

            // 5. Load pipeline config and run engines
            List<PipelineConfig.EngineEntry> engines = pipelineConfig.loadEnabledEngines();
            if (engines.isEmpty()) {
                log.error("No OCR engines configured! documentId={}", msg.documentId());
                steps.add(PipelineStep.failed("OCR", "ocr", "No Engine", "no engines configured"));
                writeback.writeFailed(msg.documentId());
                return;
            }

            // 6. Build initial context
            EngineContext ctx = EngineContext.initial(msg.documentId(), msg.documentName(),
                    catCode, msg.categoryId());

            // Load few-shot examples for LLM engines
            List<FewShotExample> examples = trainingService.loadExamples(catCode, 2);
            ctx = ctx.withExamples(examples);

            // 6b. For Office docs / native PDFs, extract embedded text via Tika as seed context
            String tikaText = extractEmbeddedText(bytes, msg.contentType(), msg.documentId());
            if (tikaText != null && !tikaText.isBlank()) {
                ctx = ctx.withPreviousResult(OcrEngineResult.textOnly(tikaText, "tika"));
                steps.add(PipelineStep.done("OCR", "ocr", "Embedded Text",
                        tikaText.length() + " chars (tika)"));
            }

            // 7. Execute engines in order
            OcrEngineResult bestResult = null;
            String acceptedEngine = null;

            for (int i = 0; i < engines.size(); i++) {
                PipelineConfig.EngineEntry entry = engines.get(i);
                OcrEnginePlugin plugin = engineRegistry.get(entry.engine());

                if (plugin == null) {
                    log.warn("Engine '{}' not found in registry, skipping", entry.engine());
                    continue;
                }

                boolean isLastEngine = (i == engines.size() - 1);
                log.info("Pipeline engine {}/{}: {} (minConfidence={}%, isLast={}), docId={}",
                        i + 1, engines.size(), entry.engine(), entry.minConfidence(),
                        isLastEngine, msg.documentId());

                // Inject engine-specific config into context
                EngineContext engineCtx = ctx.withEngineConfig(entry.config());

                // Process each page image through this engine
                OcrEngineResult result = processWithEngine(plugin, pageImages, bytes,
                        msg.contentType(), engineCtx, steps);

                if (result == null || (result.text().isBlank() && result.fields().isEmpty())) {
                    steps.add(PipelineStep.skipped("OCR", "ocr",
                            plugin.displayName(), "no result"));
                    continue;
                }

                // Text-only engine (RapidOCR) — save text, continue to next
                if (!plugin.capabilities().contains(OcrEnginePlugin.Capability.CLASSIFY)) {
                    ctx = ctx.withPreviousResult(result);
                    steps.add(PipelineStep.done("OCR", "ocr", "Text Extracted",
                            result.text().length() + " chars (" + entry.engine() + ")"));
                    // Keep this as best if nothing better comes later
                    if (bestResult == null) bestResult = result;
                    continue;
                }

                // Full engine — evaluate confidence
                BigDecimal confidence = result.confidence();
                double confValue = confidence != null ? confidence.doubleValue() : 0;
                boolean meetsThreshold = confValue >= entry.minConfidence();

                if (meetsThreshold || isLastEngine) {
                    bestResult = result;
                    acceptedEngine = entry.engine();

                    if (result.detectedCategory() != null) {
                        steps.add(PipelineStep.done("CLASSIFY", "classify", "Classified",
                                result.detectedCategory() + " (" + confidence + "%) by " + entry.engine()));
                    }
                    int fieldCount = (int) result.fields().entrySet().stream()
                            .filter(e -> !e.getKey().startsWith("_")).count();
                    if (fieldCount > 0) {
                        steps.add(PipelineStep.done("FIELDS", "ocr", "Fields Extracted",
                                fieldCount + " fields (" + entry.engine() + ")"));
                    }

                    log.info("Pipeline accepted result from {}: confidence={}%, category={}, fields={}, docId={}",
                            entry.engine(), confValue, result.detectedCategory(), fieldCount, msg.documentId());
                    break;

                } else {
                    // Below threshold — pass context to next engine
                    ctx = ctx.withPreviousResult(result);
                    steps.add(PipelineStep.skipped("CLASSIFY", "classify",
                            plugin.displayName(),
                            "confidence " + confValue + "% < " + entry.minConfidence() + "% threshold"));

                    // Try keyword classification on extracted text so next engine gets category hint
                    if (ctx.categoryCode() == null && result.text() != null
                            && result.text().length() >= MIN_USEFUL_TEXT_LENGTH) {
                        try {
                            var kwResult = documentClassifier.classify(result.text(), Map.of());
                            if (kwResult.categoryId() != null && kwResult.confidence() != null
                                    && kwResult.confidence().doubleValue() >= 40.0) {
                                log.info("Keyword classifier hint: {} ({}%), passing to next engine. docId={}",
                                        kwResult.categoryCode(), kwResult.confidence(), msg.documentId());
                                // Update context with detected category for next engine
                                ctx = new EngineContext(kwResult.categoryCode(), kwResult.categoryId(),
                                        ctx.previousText(), ctx.previousFields(),
                                        ctx.fewShotExamples(), ctx.documentId(), ctx.documentName(),
                                        ctx.engineConfig());
                                steps.add(PipelineStep.done("CLASSIFY", "classify", "Keyword Classified",
                                        kwResult.categoryCode() + " (" + kwResult.confidence() + "%)"));
                            }
                        } catch (Exception ex) {
                            log.debug("Keyword classification hint failed: {}", ex.getMessage());
                        }
                    }

                    log.info("Pipeline {} below threshold ({}% < {}%), falling through. docId={}",
                            entry.engine(), confValue, entry.minConfidence(), msg.documentId());
                }
            }

            // 8. No result from any engine
            if (bestResult == null) {
                log.warn("All engines failed to produce result for documentId={}", msg.documentId());
                steps.add(PipelineStep.failed("OCR", "ocr", "All Engines Failed", "no text extracted"));
                writeback.writeFailed(msg.documentId());
                return;
            }

            // 8b. If we have text but no category, try keyword classification as last resort
            if (bestResult.detectedCategory() == null && bestResult.text() != null
                    && bestResult.text().length() >= MIN_USEFUL_TEXT_LENGTH) {
                try {
                    var kwResult = documentClassifier.classify(bestResult.text(), Map.of());
                    if (kwResult.categoryId() != null && kwResult.confidence() != null
                            && kwResult.confidence().doubleValue() >= 40.0) {
                        log.info("Keyword classifier detected: {} ({}%) from engine text, docId={}",
                                kwResult.categoryCode(), kwResult.confidence(), msg.documentId());
                        bestResult = new OcrEngineResult(bestResult.text(), bestResult.fields(),
                                kwResult.categoryCode(), kwResult.confidence(),
                                bestResult.regions(), bestResult.engineId(), bestResult.modelUsed());
                        steps.add(PipelineStep.done("CLASSIFY", "classify", "Keyword Classified",
                                kwResult.categoryCode() + " (" + kwResult.confidence() + "%)"));
                    }
                } catch (Exception e) {
                    log.debug("Keyword classification fallback failed: {}", e.getMessage());
                }
            }

            // 9. Save Azure results as training examples for GLM-OCR
            if ("azure".equals(acceptedEngine) && bestResult.detectedCategory() != null) {
                trainingService.saveExample(bestResult.detectedCategory(), bestResult, bytes, "AZURE");
            }

            // 10. Normalize field names using DB mappings (given_names→first_name, etc.)
            String normCatCode = bestResult.detectedCategory() != null
                    ? bestResult.detectedCategory() : catCode;
            Map<String, Object> normalizedFields = fieldNormalizer.normalize(
                    normCatCode, bestResult.fields());
            bestResult = new OcrEngineResult(bestResult.text(), normalizedFields,
                    bestResult.detectedCategory(), bestResult.confidence(),
                    bestResult.regions(), bestResult.engineId(), bestResult.modelUsed());

            // 10b. Synthesize full_name from parts if normalizer didn't already (improves customer matching)
            bestResult = synthesizeFullName(bestResult);

            // 11. Customer matching from best available fields
            String partyExternalId = matchCustomer(bestResult, ctx, steps);

            // 12. Determine category ID
            Integer categoryId = resolveCategoryId(bestResult, msg.categoryId());

            // 13. Write back and publish
            writeAndPublish(msg, bestResult.text(), bestResult.fields(),
                    categoryId, bestResult.detectedCategory() != null ? "AUTO_CLASSIFIED" : null,
                    bestResult.confidence(), partyExternalId, steps.build(), start);

        } catch (Exception e) {
            log.error("OCR pipeline failed: documentId={}, error={}", msg.documentId(), e.getMessage(), e);
            writeback.writeFailed(msg.documentId());
            throw e instanceof RuntimeException re ? re : new RuntimeException(e);
        }
    }

    // ── Engine execution ────────────────────────────────────────────────────────

    /**
     * Process all page images through a single engine, concatenating results.
     */
    private OcrEngineResult processWithEngine(OcrEnginePlugin plugin, List<byte[]> pageImages,
                                               byte[] originalBytes, String contentType,
                                               EngineContext ctx, PipelineStep.Builder steps) {
        try {
            if (pageImages.isEmpty()) {
                // No page images — engine processes raw bytes directly (or uses previous text)
                return plugin.process(originalBytes, contentType, ctx);
            }

            if (pageImages.size() == 1) {
                return plugin.process(pageImages.get(0), "image/png", ctx);
            }

            // Multi-page: process each page, merge results
            StringBuilder allText = new StringBuilder();
            Map<String, Object> allFields = new LinkedHashMap<>();
            List<OcrEngineResult.Region> allRegions = new ArrayList<>();
            String detectedCategory = null;
            BigDecimal bestConfidence = null;

            for (int p = 0; p < pageImages.size(); p++) {
                OcrEngineResult pageResult = plugin.process(pageImages.get(p), "image/png", ctx);
                if (pageResult == null) continue;

                if (allText.length() > 0) allText.append("\n\n");
                if (pageImages.size() > 1) {
                    allText.append("=== Page ").append(p + 1).append(" ===\n");
                }
                allText.append(pageResult.text());

                // Merge fields (later pages don't overwrite earlier)
                pageResult.fields().forEach(allFields::putIfAbsent);
                allRegions.addAll(pageResult.regions());

                // Take highest-confidence category
                if (pageResult.confidence() != null
                        && (bestConfidence == null || pageResult.confidence().compareTo(bestConfidence) > 0)) {
                    bestConfidence = pageResult.confidence();
                    detectedCategory = pageResult.detectedCategory();
                }
            }

            return new OcrEngineResult(allText.toString(), allFields, detectedCategory,
                    bestConfidence, allRegions, plugin.engineId(), plugin.engineId());

        } catch (Exception e) {
            log.warn("Engine {} failed for documentId={}: {}",
                    plugin.engineId(), ctx.documentId(), e.getMessage());
            return null;
        }
    }

    // ── Image preparation ───────────────────────────────────────────────────────

    /**
     * Prepare images for OCR engines.
     * - Images: return as single-element list
     * - Native PDFs (embedded text): return empty list (engines use raw bytes or previous text)
     * - Scanned PDFs: render pages to PNG, return list of page images
     */
    private List<byte[]> prepareImages(byte[] bytes, String contentType,
                                        Object documentId, PipelineStep.Builder steps) {
        String bare = contentType != null ? contentType.toLowerCase().split(";")[0].trim() : "";

        // Images → use directly
        if (bare.startsWith("image/")) {
            return List.of(bytes);
        }

        // PDF → check for embedded text
        if ("application/pdf".equals(bare)) {
            return preparePdfPages(bytes, documentId, steps);
        }

        // Office/text docs → Tika extracts embedded text, no images needed
        // The engine will work with ctx.previousText
        return List.of();
    }

    /**
     * Handle PDFs: always render pages to PNG for vision-based engines.
     * Embedded text is extracted separately by Tika as seed context.
     */
    private List<byte[]> preparePdfPages(byte[] pdfBytes, Object documentId, PipelineStep.Builder steps) {
        log.info("Rendering PDF pages to PNG for OCR engines, docId={}", documentId);
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            int totalPages = doc.getNumberOfPages();
            int pagesToRender = Math.min(totalPages, props.getScannedPdfMaxPages());
            PDFRenderer renderer = new PDFRenderer(doc);
            List<byte[]> pageImages = new ArrayList<>();

            for (int i = 0; i < pagesToRender; i++) {
                BufferedImage pageImage = renderer.renderImageWithDPI(
                        i, props.getScannedPdfRenderDpi(), ImageType.RGB);

                try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                    ImageIO.write(pageImage, "png", baos);
                    pageImages.add(baos.toByteArray());
                }
                //noinspection UnusedAssignment
                pageImage = null; // allow GC before next page
            }

            log.info("Rendered {} PDF pages as PNG, docId={}", pageImages.size(), documentId);
            return pageImages;

        } catch (Exception e) {
            log.error("PDF page rendering failed for docId={}: {}", documentId, e.getMessage());
            steps.add(PipelineStep.failed("OCR", "ocr", "PDF Render Failed", e.getMessage()));
            return List.of();
        }
    }

    // ── Embedded text extraction ───────────────────────────────────────────────

    /**
     * Extract embedded text from PDFs and Office docs via Tika.
     * Returns null for images (no embedded text).
     */
    private String extractEmbeddedText(byte[] bytes, String contentType, Object documentId) {
        String bare = contentType != null ? contentType.toLowerCase().split(";")[0].trim() : "";
        if (bare.startsWith("image/")) return null; // images have no embedded text

        try {
            String text = tikaEngine.extract(new ByteArrayInputStream(bytes), contentType, documentId);
            return text != null && !text.isBlank() ? text.strip() : null;
        } catch (Exception e) {
            log.debug("Tika embedded text extraction failed: {}", e.getMessage());
            return null;
        }
    }

    // ── Customer matching ───────────────────────────────────────────────────────

    /**
     * Synthesize full_name from first_name + middle_name + last_name if missing.
     * Also synthesizes passenger_name from parts.
     *
     * <p>Note: Alternate name normalization (given_names→first_name, surname→last_name)
     * is now handled by {@link FieldNormalizerService} — this method only synthesizes
     * composite names from already-normalized parts.</p>
     */
    private OcrEngineResult synthesizeFullName(OcrEngineResult result) {
        if (result.fields() == null || result.fields().isEmpty()) return result;

        Map<String, Object> fields = new LinkedHashMap<>(result.fields());
        boolean changed = false;

        // Synthesize full_name from parts (if FieldNormalizerService didn't already)
        if (!fields.containsKey("full_name") || isBlankValue(fields.get("full_name"))) {
            String fullName = composeName(fields, "first_name", "middle_name", "last_name");
            if (fullName != null) {
                fields.put("full_name", fullName);
                changed = true;
                log.debug("Synthesized full_name: {}", fullName);
            }
        }

        // Synthesize passenger_name from parts (boarding passes)
        if (!fields.containsKey("passenger_name") && fields.containsKey("passenger_first_name")) {
            String name = composeName(fields, "passenger_first_name", null, "passenger_last_name");
            if (name != null) { fields.put("passenger_name", name); changed = true; }
        }

        if (!changed) return result;

        return new OcrEngineResult(result.text(), fields, result.detectedCategory(),
                result.confidence(), result.regions(), result.engineId(), result.modelUsed());
    }

    private static boolean isBlankValue(Object value) {
        return value == null || value.toString().isBlank();
    }

    /** Get first non-blank string value from multiple field name candidates. */
    private static String fieldStr(Map<String, Object> fields, String... keys) {
        for (String key : keys) {
            Object v = fields.get(key);
            if (v != null && !v.toString().isBlank()) return v.toString().trim();
        }
        return null;
    }

    private static String composeName(Map<String, Object> fields, String firstKey,
                                       String middleKey, String lastKey) {
        String first = fields.get(firstKey) != null ? fields.get(firstKey).toString().trim() : null;
        String middle = middleKey != null && fields.get(middleKey) != null
                ? fields.get(middleKey).toString().trim() : null;
        String last = fields.get(lastKey) != null ? fields.get(lastKey).toString().trim() : null;

        if (first == null && last == null) return null;

        StringBuilder sb = new StringBuilder();
        if (first != null && !first.isBlank()) sb.append(first);
        if (middle != null && !middle.isBlank()) sb.append(" ").append(middle);
        if (last != null && !last.isBlank()) {
            if (!sb.isEmpty()) sb.append(" ");
            sb.append(last);
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private String matchCustomer(OcrEngineResult result, EngineContext ctx, PipelineStep.Builder steps) {
        Map<String, String> fieldsAsStrings = new HashMap<>();
        if (result.fields() != null) {
            result.fields().forEach((k, v) -> { if (v != null) fieldsAsStrings.put(k, v.toString()); });
        }

        String bestText = result.text() != null && !result.text().isBlank()
                ? result.text() : ctx.previousText();

        try {
            CustomerMatchResult custResult = customerMatcher.match(bestText, fieldsAsStrings);
            if (custResult != null && custResult.externalId() != null) {
                log.info("Customer matched: {} confidence={}%, externalId={}, docId={}",
                        custResult.matchedField(), custResult.confidence(),
                        custResult.externalId(), ctx.documentId());
                steps.add(PipelineStep.done("CUSTOMER", "classify", "Customer Matched",
                        custResult.matchedField() + " (" + custResult.confidence() + "%)"));
                return custResult.externalId();
            } else {
                steps.add(PipelineStep.skipped("CUSTOMER", "classify", "No Match",
                        "no identifier found"));
            }
        } catch (Exception e) {
            log.warn("Customer matching failed: {}", e.getMessage());
            steps.add(PipelineStep.failed("CUSTOMER", "classify", "Match Failed", e.getMessage()));
        }
        return null;
    }

    // ── Status resolution ───────────────────────────────────────────────────────

    /**
     * Resolves the target status for a document after OCR processing.
     *
     * <p>Rules (in order):</p>
     * <ol>
     *   <li><b>Manual upload</b> → ACTIVE — the user provided full context (category, customer, etc.)</li>
     *   <li><b>No category</b> → PENDING_CLASSIFICATION — document type unknown, reviewer must classify</li>
     *   <li><b>Category + customer matched</b> → ACTIVE — fully linked, ready for use</li>
     *   <li><b>Category but no customer</b> → NEEDS_ASSIGNMENT — orphaned, reviewer must assign or create a customer</li>
     * </ol>
     *
     * <p>The old behavior of "high classification confidence alone → ACTIVE" was
     * intentionally removed. High classification confidence means "we know what
     * TYPE of document this is" — not "this document is ready to use". An unlinked
     * document with no owner is a compliance/audit risk in a financial ECM and
     * should always go to human review.</p>
     *
     * <p>The {@code confidence} parameter is retained for logging and future
     * use (e.g., per-category thresholds via {@code CompositeConfidenceScorer}).</p>
     */
    @SuppressWarnings("unused") // confidence reserved for future composite scoring integration
    private String resolveTargetStatus(Integer categoryId, BigDecimal confidence,
                                        String partyExternalId, boolean manualUpload) {
        if (manualUpload) return "ACTIVE";
        if (categoryId == null) return "PENDING_CLASSIFICATION";
        boolean customerMatched = partyExternalId != null && !partyExternalId.isBlank();
        return customerMatched ? "ACTIVE" : "NEEDS_ASSIGNMENT";
    }

    // ── Category resolution ─────────────────────────────────────────────────────

    private Integer resolveCategoryId(OcrEngineResult result, Integer manualCategoryId) {
        if (manualCategoryId != null) return manualCategoryId;
        if (result.detectedCategory() == null) return null;

        // Look up category ID from code
        try {
            return jdbc.queryForObject(
                    "SELECT id FROM ecm_admin.document_categories WHERE UPPER(code) = UPPER(?)",
                    Integer.class, result.detectedCategory());
        } catch (EmptyResultDataAccessException e) {
            log.warn("Detected category '{}' not found in document_categories", result.detectedCategory());
            return null;
        } catch (Exception e) {
            log.warn("Failed to resolve category ID for '{}': {}", result.detectedCategory(), e.getMessage());
            return null;
        }
    }

    private String resolveCategoryCode(Integer categoryId) {
        if (categoryId == null) return null;
        try {
            return jdbc.queryForObject(CATEGORY_CODE_SQL, String.class, categoryId);
        } catch (Exception e) {
            log.warn("Failed to resolve category code for id={}: {}", categoryId, e.getMessage());
            return null;
        }
    }

    // ── Write back + publish ────────────────────────────────────────────────────

    private void writeAndPublish(OcrRequestMessage msg, String text, Map<String, Object> fields,
                                  Integer categoryId, String classificationSource,
                                  BigDecimal confidence, String partyExternalId,
                                  List<PipelineStep> steps, long startTime) {

        boolean manualUpload = msg.categoryId() != null;
        if (categoryId == null) categoryId = msg.categoryId();

        String targetStatus = resolveTargetStatus(categoryId, confidence, partyExternalId, manualUpload);

        // Write back to DB
        if (categoryId != null || partyExternalId != null) {
            writeback.writeSuccessWithClassification(msg.documentId(), text, fields,
                    categoryId, classificationSource, confidence,
                    partyExternalId, steps, targetStatus);
        } else {
            writeback.writeSuccess(msg.documentId(), text, fields, steps, targetStatus);
        }

        // Index into OpenSearch
        indexService.index(msg, text, fields);

        // Publish event
        OcrCompletedEvent event = new OcrCompletedEvent(
                msg.documentId(), msg.documentName(),
                text, fields, false, 0, OffsetDateTime.now(),
                categoryId, classificationSource, confidence, partyExternalId);
        rabbit.convertAndSend(COMPLETED_EXCHANGE, "", event);

        // Push to AI Gateway RAG (fire-and-forget, non-blocking)
        pushToRagWebhook(msg.documentId(), msg.documentName(), text, fields,
                categoryId != null ? resolveCategoryCode(categoryId) : null);

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("OCR pipeline complete: documentId={}, elapsed={}ms, classified={}, status={}",
                msg.documentId(), elapsed, categoryId != null, targetStatus);
    }

    /**
     * Push OCR results to AI Gateway's RAG ingestion webhook.
     * Fire-and-forget — failure does not affect the OCR pipeline.
     *
     * <p>The request is HMAC-signed using the shared secret stored in
     * {@code ecm_admin.tenant_config} under key {@code ai.gateway.webhook.hmac.secret}.
     * The AI Gateway's {@code WebhookAuthFilter} validates the {@code X-Webhook-Signature}
     * header and rejects any request that is unsigned or has a bad signature.
     *
     * <p>If the secret is missing or blank, this method logs a warning and skips
     * the push — we never send unsigned requests, even though they'd just be rejected.
     */
    private void pushToRagWebhook(UUID documentId, String documentName, String text,
                                   Map<String, Object> fields, String categoryCode) {
        if (text == null || text.length() < 50) return; // skip tiny/empty text

        try {
            String webhookUrl = resolveRagWebhookUrl();
            if (webhookUrl == null) return;

            String secret = resolveRagWebhookSecret();
            if (secret == null || secret.isBlank()) {
                log.warn("RAG webhook push skipped: HMAC secret not configured (tenant_config key 'ai.gateway.webhook.hmac.secret'). " +
                        "Configure it via Admin → Integrations → AI Gateway.");
                return;
            }

            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("documentId", documentId.toString());
            payload.put("documentName", documentName);
            payload.put("extractedText", text);
            if (categoryCode != null) payload.put("categoryCode", categoryCode);
            if (fields != null && !fields.isEmpty()) payload.put("extractedFields", fields);

            String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload);
            byte[] bodyBytes = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            String signature = "sha256=" + hmacSha256Hex(bodyBytes, secret);

            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(webhookUrl))
                    .header("Content-Type", "application/json")
                    .header("X-Webhook-Signature", signature)
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(bodyBytes))
                    .timeout(java.time.Duration.ofSeconds(5))
                    .build();

            // Fire-and-forget — use sendAsync
            java.net.http.HttpClient.newHttpClient().sendAsync(request,
                    java.net.http.HttpResponse.BodyHandlers.ofString())
                    .thenAccept(r -> {
                        if (r.statusCode() == 401 || r.statusCode() == 403) {
                            log.warn("RAG webhook rejected ({}) for docId={} — HMAC secret may be out of sync with AI Gateway",
                                    r.statusCode(), documentId);
                        } else {
                            log.debug("RAG webhook response: {} for docId={}", r.statusCode(), documentId);
                        }
                    })
                    .exceptionally(e -> { log.debug("RAG webhook failed for docId={}: {}", documentId, e.getMessage()); return null; });

        } catch (Exception e) {
            log.debug("RAG webhook push failed (non-fatal): {}", e.getMessage());
        }
    }

    private String resolveRagWebhookUrl() {
        try {
            return jdbc.queryForObject(
                    "SELECT value FROM ecm_admin.tenant_config WHERE key = 'ai.gateway.webhook.url'",
                    String.class);
        } catch (Exception e) {
            return null; // not configured — skip RAG push
        }
    }

    // ── HMAC secret cache ─────────────────────────────────────────────────────
    // 60-second TTL. After an admin rotates the secret in the Integrations UI,
    // outbound webhooks resume within 60s. No cross-pod invalidation — acceptable
    // because rotation is a deliberate, announced operation.
    private static final long SECRET_CACHE_TTL_MS = 60_000L;
    private volatile String cachedRagWebhookSecret;
    private volatile long   cachedRagWebhookSecretAt;

    private String resolveRagWebhookSecret() {
        long now = System.currentTimeMillis();
        String cached = cachedRagWebhookSecret;
        if (cached != null && (now - cachedRagWebhookSecretAt) < SECRET_CACHE_TTL_MS) {
            return cached;
        }
        try {
            String fresh = jdbc.queryForObject(
                    "SELECT value FROM ecm_admin.tenant_config WHERE key = 'ai.gateway.webhook.hmac.secret'",
                    String.class);
            cachedRagWebhookSecret = fresh;
            cachedRagWebhookSecretAt = now;
            return fresh;
        } catch (Exception e) {
            // Don't poison the cache on transient DB errors — serve stale value if we have one
            if (cached != null) return cached;
            return null;
        }
    }

    private static String hmacSha256Hex(byte[] body, String secret) throws Exception {
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(
                secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] hash = mac.doFinal(body);
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
