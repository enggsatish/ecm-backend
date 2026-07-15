package com.ecm.batch.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Post-OCR auto-classification for single document uploads.
 *
 * <p>Classification logic now lives entirely in ecm-ocr's pipeline.
 * This service is retained only as an entry point for the RabbitMQ consumer
 * (BatchItemConsumer) to log the event. The OCR pipeline already classifies
 * and writes back results before this is invoked.</p>
 *
 * <p>If the OCR pipeline couldn't classify (confidence too low), the document
 * stays as "Needs Classification" — no retry here.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AutoClassifyService {

    /**
     * Called by BatchItemConsumer when a POST_OCR_FALLBACK message arrives.
     * The OCR pipeline already attempted classification; this is just a log hook.
     */
    public void classifyDocument(UUID documentId, String storageBucket, String storageKey, String originalFilename) {
        log.info("Post-OCR classification event for documentId={} — classification handled by OCR pipeline", documentId);
        // Classification is now done inside OcrPipelineService.classifyAndExtract().
        // If the OCR pipeline could not classify with sufficient confidence,
        // the document stays as "Needs Classification" for manual review.
        // No retry logic here — avoids double classification.
    }
}
