package com.ecm.ocr.service;

import com.ecm.common.client.AdminServiceClient;
import com.ecm.ocr.pipeline.PipelineStep;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Writes OCR results back to ecm_core.documents via JdbcTemplate.
 *
 * This module does NOT own the documents table (ecm-document does).
 * Per platform convention, cross-schema writes MUST use JdbcTemplate,
 * never JPA. Flyway migrations for ecm_core run only from ecm-document.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentWritebackService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final AdminServiceClient adminServiceClient;

    /**
     * No classification — text only or text + fields.
     * Status: PENDING_OCR → target status (passed by caller).
     */
    private static final String UPDATE_NO_CLASSIFICATION_SQL = """
        UPDATE ecm_core.documents
           SET ocr_completed     = true,
               extracted_text    = ?,
               extracted_fields  = COALESCE(?::jsonb, extracted_fields),
               pipeline_state    = COALESCE(pipeline_state, '[]'::jsonb) || COALESCE(?::jsonb, '[]'::jsonb),
               status            = CASE
                                     WHEN status = 'PENDING_OCR' THEN ?
                                     ELSE status
                                   END,
               updated_at        = NOW()
         WHERE id = ?
        """;

    /**
     * With classification — text, fields, category, customer, confidence.
     * Status: PENDING_OCR → target status (passed by caller).
     */
    private static final String UPDATE_WITH_CLASSIFICATION_SQL = """
        UPDATE ecm_core.documents
           SET ocr_completed             = true,
               extracted_text            = ?,
               extracted_fields          = COALESCE(?::jsonb, extracted_fields),
               category_id               = COALESCE(?, category_id),
               classification_source     = COALESCE(?, classification_source),
               classification_confidence = COALESCE(?, classification_confidence),
               party_external_id         = COALESCE(?, party_external_id),
               pipeline_state            = COALESCE(pipeline_state, '[]'::jsonb) || COALESCE(?::jsonb, '[]'::jsonb),
               status                    = CASE
                                             WHEN status = 'PENDING_OCR' THEN ?
                                             ELSE status
                                           END,
               updated_at                = NOW()
         WHERE id = ?
        """;

    /** OCR failed — only set OCR_FAILED if currently PENDING_OCR */
    private static final String FAIL_SQL = """
        UPDATE ecm_core.documents
           SET status     = CASE
                              WHEN status = 'PENDING_OCR' THEN 'OCR_FAILED'
                              ELSE status
                            END,
               updated_at = NOW()
         WHERE id = ?
        """;

    /**
     * Write OCR results without classification.
     * Document transitions to the given target status.
     */
    public void writeSuccess(UUID documentId, String extractedText,
                             Map<String, Object> extractedFields,
                             List<PipelineStep> pipelineSteps, String targetStatus) {
        try {
            String fieldsJson = extractedFields != null && !extractedFields.isEmpty()
                    ? objectMapper.writeValueAsString(extractedFields) : null;
            String stepsJson = pipelineSteps != null ? objectMapper.writeValueAsString(pipelineSteps) : null;

            int rows = jdbc.update(UPDATE_NO_CLASSIFICATION_SQL,
                    extractedText, fieldsJson, stepsJson, targetStatus, documentId);
            log.info("OCR writeback success: documentId={}, status={}, rows={}",
                    documentId, targetStatus, rows);
        } catch (Exception e) {
            log.error("OCR writeback failed for documentId={}: {}", documentId, e.getMessage(), e);
            throw new WritebackException("Writeback failed for " + documentId, e);
        }
    }

    /**
     * Write OCR results with classification data.
     * Document transitions to the given target status.
     */
    public void writeSuccessWithClassification(UUID documentId, String extractedText,
            Map<String, Object> extractedFields, Integer categoryId,
            String classificationSource, java.math.BigDecimal confidence,
            String partyExternalId, List<PipelineStep> pipelineSteps,
            String targetStatus) {
        try {
            String fieldsJson = extractedFields != null && !extractedFields.isEmpty()
                    ? objectMapper.writeValueAsString(extractedFields) : null;
            String stepsJson = pipelineSteps != null ? objectMapper.writeValueAsString(pipelineSteps) : null;

            int rows = jdbc.update(UPDATE_WITH_CLASSIFICATION_SQL,
                    extractedText, fieldsJson, categoryId, classificationSource,
                    confidence, partyExternalId, stepsJson, targetStatus, documentId);
            log.info("OCR writeback with classification: documentId={}, categoryId={}, status={}, rows={}",
                    documentId, categoryId, targetStatus, rows);

            // Resolve hierarchy (segment, product line) from category
            resolveAndWriteHierarchy(documentId, categoryId);
        } catch (Exception e) {
            log.error("OCR writeback with classification failed for documentId={}: {}",
                    documentId, e.getMessage(), e);
            // Fallback: write OCR results without classification
            writeSuccess(documentId, extractedText, extractedFields, pipelineSteps, targetStatus);
        }
    }

    public void writeFailed(UUID documentId) {
        try {
            jdbc.update(FAIL_SQL, documentId);
            log.warn("OCR marked as FAILED: documentId={}", documentId);
        } catch (Exception e) {
            log.error("Failed to mark OCR_FAILED for {}: {}", documentId, e.getMessage(), e);
        }
    }

    /**
     * Resolve segment and product line from category via ecm-admin hierarchy,
     * then update the document. Non-fatal — logs warning on failure.
     */
    private void resolveAndWriteHierarchy(UUID documentId, Integer categoryId) {
        if (categoryId == null) return;
        try {
            Map<String, Object> hierarchy = adminServiceClient.resolveHierarchy(categoryId);
            if (hierarchy.isEmpty()) {
                log.debug("No hierarchy mapping for categoryId={}", categoryId);
                return;
            }

            Integer segmentId = hierarchy.get("segment_id") instanceof Number n ? n.intValue() : null;
            Integer productLineId = hierarchy.get("product_line_id") instanceof Number n ? n.intValue() : null;

            if (segmentId == null && productLineId == null) return;

            jdbc.update("""
                UPDATE ecm_core.documents
                   SET segment_id      = COALESCE(?, segment_id),
                       product_line_id = COALESCE(?, product_line_id),
                       updated_at      = NOW()
                 WHERE id = ? AND (segment_id IS NULL OR product_line_id IS NULL)
                """, segmentId, productLineId, documentId);

            log.info("Hierarchy resolved for documentId={}: segmentId={}, productLineId={}",
                    documentId, segmentId, productLineId);
        } catch (Exception e) {
            log.warn("Hierarchy resolution failed for documentId={}, categoryId={}: {}",
                    documentId, categoryId, e.getMessage());
        }
    }

    public static class WritebackException extends RuntimeException {
        public WritebackException(String msg, Throwable cause) { super(msg, cause); }
    }
}
