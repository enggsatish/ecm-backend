package com.ecm.batch.messaging;

import com.ecm.batch.config.RabbitConfig;
import com.ecm.batch.service.AutoClassifyService;
import com.ecm.batch.service.BatchProcessingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class BatchItemConsumer {

    private final BatchProcessingService batchProcessingService;
    private final AutoClassifyService autoClassifyService;

    @RabbitListener(queues = RabbitConfig.ITEM_PROCESS_QUEUE)
    public void handleProcessMessage(Map<String, Object> rawMessage) {
        try {
            UUID batchId = parseUuid(rawMessage.get("batchId"));
            UUID itemId = parseUuid(rawMessage.get("itemId"));
            UUID documentId = parseUuid(rawMessage.get("documentId"));
            String storageBucket = (String) rawMessage.getOrDefault("storageBucket", "ecm-documents");
            String storageKey = (String) rawMessage.get("storageKey");
            String originalFilename = (String) rawMessage.getOrDefault("originalFilename", "");
            String source = (String) rawMessage.getOrDefault("source", "BATCH");

            if (documentId == null) {
                log.warn("Message missing documentId — skipping: {}", rawMessage);
                return;
            }

            if (batchId != null && itemId != null) {
                // Standard batch flow — BatchItem already exists
                log.info("Processing batch item: batchId={}, itemId={}, docId={}", batchId, itemId, documentId);
                batchProcessingService.processItem(new BatchItemMessage(
                        batchId, itemId, documentId, storageBucket, storageKey, originalFilename));
            } else {
                // Auto-classify flow — single upload, no batch context yet
                log.info("Auto-classify request for documentId={} (source={})", documentId, source);
                autoClassifyService.classifyDocument(documentId, storageBucket, storageKey, originalFilename);
            }
        } catch (Exception e) {
            log.error("Error processing message: {}", e.getMessage(), e);
            throw e;
        }
    }

    private UUID parseUuid(Object obj) {
        if (obj == null) return null;
        try {
            return UUID.fromString(obj.toString());
        } catch (Exception e) {
            return null;
        }
    }
}
