package com.ecm.ocr.pipeline;

import com.ecm.ocr.engine.OcrEngine;
import com.ecm.ocr.event.OcrCompletedEvent;
import com.ecm.ocr.event.OcrRequestMessage;
import com.ecm.ocr.properties.OcrProperties;
import com.ecm.ocr.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Orchestrates the full OCR pipeline:
 *
 *  1. Fetch document bytes from MinIO
 *  2. Skip oversized files (index metadata only)
 *  3. Extract text via Tika
 *  4. Apply field extraction templates
 *  5. Write extracted_text + extracted_fields back to ecm_core.documents
 *  6. Index into OpenSearch (graceful degradation on failure)
 *  7. Publish OcrCompletedEvent to ecm.ocr.completed fanout
 *
 * On any failure: mark document as OCR_FAILED, then rethrow so
 * the listener NAKs and RabbitMQ sends the message to the DLQ.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OcrPipelineService {

    private final MinioFetchService         minioFetch;
    private final OcrEngine                 ocrEngine;
    private final FieldExtractorService     fieldExtractor;
    private final DocumentWritebackService  writeback;
    private final DocumentIndexService      indexService;
    private final RabbitTemplate            rabbit;
    private final OcrProperties             props;

    private static final String COMPLETED_EXCHANGE = "ecm.ocr.completed";

    public void process(OcrRequestMessage msg) {
        log.info("OCR pipeline start: documentId={}, key={}",
                msg.documentId(), msg.storageKey());
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
                // 3. Extract text
                extractedText = ocrEngine.extract(
                        new ByteArrayInputStream(bytes), msg.contentType());

                // 4. Field extraction from template
                // categoryId is a UUID; we need the category CODE for template lookup.
                // For Sprint D: fall back to categoryId string as code.
                // Sprint E will resolve code from Redis cache.
                String catCode = msg.categoryId() != null
                        ? msg.categoryId().toString() : null;
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
                    0,   // page count not implemented in Sprint D
                    OffsetDateTime.now()
            );
            rabbit.convertAndSend(COMPLETED_EXCHANGE, "", event);

            long elapsed = System.currentTimeMillis() - start;
            log.info("OCR pipeline complete: documentId={}, elapsed={}ms, fields={}",
                    msg.documentId(), elapsed, extractedFields.size());

        } catch (Exception e) {
            log.error("OCR pipeline failed: documentId={}, error={}",
                    msg.documentId(), e.getMessage(), e);
            writeback.writeFailed(msg.documentId());
            // Rethrow → listener will NAK → message goes to DLQ
            throw e instanceof RuntimeException re ? re : new RuntimeException(e);
        }
    }
}
