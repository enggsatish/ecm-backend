package com.ecm.ocr.pipeline;

import com.ecm.ocr.engine.OcrEngine;
import com.ecm.ocr.event.OcrCompletedEvent;
import com.ecm.ocr.event.OcrRequestMessage;
import com.ecm.ocr.properties.OcrProperties;
import com.ecm.ocr.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Orchestrates the full OCR pipeline:
 *
 *  1. Fetch document bytes from MinIO
 *  2. Skip oversized files (index metadata only)
 *  3. Extract text via OcrEngine (Tesseract HTTP or Tika)
 *  4. Resolve category CODE from integer categoryId → apply extraction template
 *  5. Write extracted_text + extracted_fields back to ecm_core.documents
 *  6. Index into OpenSearch (graceful degradation on failure)
 *  7. Publish OcrCompletedEvent to ecm.ocr.completed fanout
 *
 * On any failure: mark document as OCR_FAILED, then rethrow so
 * the listener NAKs and RabbitMQ sends the message to the DLQ.
 *
 * ── Category code resolution (fix for Sprint D) ───────────────────────────────
 * Previously this class passed msg.categoryId().toString() → "1", "2", "3" to
 * FieldExtractorService. But extraction templates are keyed by category CODE
 * (e.g. "MORTGAGE", "IDENTITY", "BOARDINGPASS") from inside the JSON file.
 * The integer → string conversion never matched any template.
 *
 * Fix: resolve the code from ecm_admin.document_categories via JdbcTemplate.
 * This is a single indexed PK lookup — negligible overhead per document.
 * JdbcTemplate is safe to use here (ecm-ocr uses it for cross-schema writes
 * via DocumentWritebackService, same pattern).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OcrPipelineService {

    private final MinioFetchService        minioFetch;
    private final OcrEngine                ocrEngine;
    private final FieldExtractorService    fieldExtractor;
    private final DocumentWritebackService writeback;
    private final DocumentIndexService     indexService;
    private final RabbitTemplate           rabbit;
    private final OcrProperties            props;
    private final JdbcTemplate             jdbc;          // ← added for category code lookup

    private static final String COMPLETED_EXCHANGE = "ecm.ocr.completed";

    /** SQL to resolve category code from its integer PK. */
    private static final String CATEGORY_CODE_SQL =
            "SELECT code FROM ecm_admin.document_categories WHERE id = ?";

    public void process(OcrRequestMessage msg) {
        log.info("OCR pipeline start: documentId={}, key={}, contentType={}",
                msg.documentId(), msg.storageKey(), msg.contentType());
        long start = System.currentTimeMillis();

        try {
            // 1. Fetch bytes from MinIO
            byte[] bytes = minioFetch.fetchBytes(msg.storageBucket(), msg.storageKey());

            String extractedText;
            Map<String, Object> extractedFields;

            // 2. Skip oversized files
            if (bytes.length > props.getMaxFileSizeBytes()) {
                log.warn("File too large for OCR: {} bytes, documentId={}",
                        bytes.length, msg.documentId());
                extractedText   = "";
                extractedFields = Map.of("_skipped", "file_too_large");

            } else {
                // 3. Extract text — routes to Tesseract HTTP or Tika based on contentType
                extractedText = ocrEngine.extract(
                        new ByteArrayInputStream(bytes),
                        msg.contentType(),
                        msg.documentId());

                // 4. Resolve category CODE for template lookup
                //
                // msg.categoryId() is the integer PK from ecm_admin.document_categories.
                // FieldExtractorService keys templates by the category CODE string
                // (e.g. "MORTGAGE", "IDENTITY") read from inside the JSON template file.
                // We look the code up here once per document — single PK lookup, fast.
                String catCode = resolveCategoryCode(msg.categoryId());
                log.debug("Category resolved: id={} → code={} | docId={}",
                        msg.categoryId(), catCode, msg.documentId());

                extractedFields = fieldExtractor.extract(catCode, extractedText);
            }

            // 5. Write back to DB
            writeback.writeSuccess(msg.documentId(), extractedText, extractedFields);

            // 6. Index into OpenSearch (non-fatal)
            indexService.index(msg, extractedText, extractedFields);

            // 7. Publish completion event
            OcrCompletedEvent event = new OcrCompletedEvent(
                    msg.documentId(), msg.documentName(),
                    extractedText, extractedFields,
                    props.isTesseractEnabled(),
                    0,
                    OffsetDateTime.now()
            );
            rabbit.convertAndSend(COMPLETED_EXCHANGE, "", event);

            long elapsed = System.currentTimeMillis() - start;
            log.info("OCR pipeline complete: documentId={}, elapsed={}ms, fields={}, chars={}",
                    msg.documentId(), elapsed, extractedFields.size(), extractedText.length());

        } catch (Exception e) {
            log.error("OCR pipeline failed: documentId={}, error={}",
                    msg.documentId(), e.getMessage(), e);
            writeback.writeFailed(msg.documentId());
            throw e instanceof RuntimeException re ? re : new RuntimeException(e);
        }
    }

    /**
     * Looks up the category CODE string for a given integer category ID.
     *
     * Returns null (→ no template applied) when:
     *   - categoryId is null (document uploaded without a category)
     *   - no row found for the given ID (stale/deleted category)
     *   - DB lookup fails for any reason (logged at WARN, pipeline continues)
     *
     * @param categoryId integer PK from ecm_admin.document_categories, may be null
     * @return category code e.g. "MORTGAGE", or null if not resolvable
     */
    private String resolveCategoryCode(Integer categoryId) {
        if (categoryId == null) {
            return null;
        }
        try {
            return jdbc.queryForObject(CATEGORY_CODE_SQL, String.class, categoryId);
        } catch (EmptyResultDataAccessException e) {
            log.warn("No document_category found for id={} — field extraction skipped", categoryId);
            return null;
        } catch (Exception e) {
            log.warn("Failed to resolve category code for id={}: {} — field extraction skipped",
                    categoryId, e.getMessage());
            return null;
        }
    }
}