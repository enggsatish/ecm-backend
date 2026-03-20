package com.ecm.ocr.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

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

    /** OCR extracted fields AND text — overwrites extracted_fields */
    private static final String UPDATE_WITH_FIELDS_SQL = """
        UPDATE ecm_core.documents
           SET ocr_completed     = true,
               extracted_text    = ?,
               extracted_fields  = ?::jsonb,
               status            = 'ACTIVE',
               updated_at        = NOW()
         WHERE id = ?
        """;

    /** OCR text only — preserves existing extracted_fields (e.g. from form submission data) */
    private static final String UPDATE_TEXT_ONLY_SQL = """
        UPDATE ecm_core.documents
           SET ocr_completed     = true,
               extracted_text    = ?,
               status            = 'ACTIVE',
               updated_at        = NOW()
         WHERE id = ?
        """;

    private static final String FAIL_SQL = """
        UPDATE ecm_core.documents
           SET status     = 'OCR_FAILED',
               updated_at = NOW()
         WHERE id = ?
        """;

    public void writeSuccess(UUID documentId, String extractedText,
                             Map<String, Object> extractedFields) {
        try {
            if (extractedFields != null && !extractedFields.isEmpty()) {
                // OCR extracted structured fields — write them (overwrites any existing)
                String fieldsJson = objectMapper.writeValueAsString(extractedFields);
                int rows = jdbc.update(UPDATE_WITH_FIELDS_SQL, extractedText, fieldsJson, documentId);
                log.info("OCR writeback success (with fields): documentId={}, fields={}, rows={}",
                        documentId, extractedFields.size(), rows);
            } else {
                // No OCR fields extracted — only update text, preserve existing extracted_fields
                // (which may have been pre-populated from form submission_data)
                int rows = jdbc.update(UPDATE_TEXT_ONLY_SQL, extractedText, documentId);
                log.info("OCR writeback success (text only, fields preserved): documentId={}, rows={}",
                        documentId, rows);
            }
        } catch (Exception e) {
            log.error("OCR writeback failed for documentId={}: {}", documentId, e.getMessage(), e);
            throw new WritebackException("Writeback failed for " + documentId, e);
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

    public static class WritebackException extends RuntimeException {
        public WritebackException(String msg, Throwable cause) { super(msg, cause); }
    }
}
