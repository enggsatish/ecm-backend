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

    private static final String UPDATE_SQL = """
        UPDATE ecm_core.documents
           SET ocr_completed     = true,
               extracted_text    = ?,
               extracted_fields  = ?::jsonb,
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
            String fieldsJson = extractedFields == null || extractedFields.isEmpty()
                    ? null
                    : objectMapper.writeValueAsString(extractedFields);

            int rows = jdbc.update(UPDATE_SQL, extractedText, fieldsJson, documentId);
            log.info("OCR writeback success: documentId={}, rows={}", documentId, rows);
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
