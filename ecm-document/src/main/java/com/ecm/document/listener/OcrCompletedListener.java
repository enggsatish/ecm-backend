package com.ecm.document.listener;

import com.ecm.document.config.RabbitMqConfig;
import com.ecm.document.entity.Document;
import com.ecm.document.entity.DocumentStatus;
import com.ecm.document.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Listens for OCR-completed events published by ecm-ocr on the
 * {@code ecm.ocr.completed} fanout exchange.
 *
 * <p>The OCR pipeline (ecm-ocr) now handles classification, customer matching,
 * and sets the appropriate document status:
 * <ul>
 *   <li>ACTIVE — fully classified (high confidence + customer matched)</li>
 *   <li>NEEDS_ASSIGNMENT — category detected but needs human fix</li>
 *   <li>PENDING_CLASSIFICATION — no category detected</li>
 * </ul>
 *
 * <p>This listener publishes downstream events (document.classified + workflow trigger)
 * ONLY when the document reached ACTIVE status (high confidence path).</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OcrCompletedListener {

    private final DocumentRepository documentRepository;
    private final RabbitTemplate     rabbitTemplate;

    @RabbitListener(queues = RabbitMqConfig.OCR_COMPLETED_DOCUMENT_Q)
    public void onOcrCompleted(Map<String, Object> event) {
        UUID documentId = null;
        try {
            Object idObj = event.get("documentId");
            if (idObj == null) {
                log.warn("OCR completed event missing documentId — skipping");
                return;
            }
            documentId = UUID.fromString(idObj.toString());

            Document doc = documentRepository.findByIdAndStatusNot(documentId, DocumentStatus.DELETED)
                    .orElse(null);
            if (doc == null) return;

            log.info("OCR completed for documentId={}, status={}, categoryId={}, partyExternalId={}",
                    documentId, doc.getStatus(), doc.getCategoryId(), doc.getPartyExternalId());

            // Publish classified event if category was determined (ACTIVE or NEEDS_ASSIGNMENT)
            // This triggers case auto-attach and workflow start
            if (doc.getCategoryId() != null && (doc.getStatus() == DocumentStatus.ACTIVE
                    || doc.getStatus() == DocumentStatus.NEEDS_ASSIGNMENT)) {
                publishClassifiedEvent(doc);
                // Only trigger workflow for ACTIVE (fully processed) documents
                if (doc.getStatus() == DocumentStatus.ACTIVE) {
                    publishWorkflowTrigger(doc);
                }
            }

        } catch (Exception e) {
            log.error("Error processing OCR completed event for documentId={}: {}",
                    documentId, e.getMessage(), e);
        }
    }

    private void publishClassifiedEvent(Document doc) {
        try {
            Map<String, Object> classifiedEvent = new java.util.HashMap<>();
            classifiedEvent.put("documentId", doc.getId().toString());
            classifiedEvent.put("categoryId", doc.getCategoryId());
            classifiedEvent.put("classificationSource", doc.getClassificationSource() != null
                    ? doc.getClassificationSource() : "AUTO_CLASSIFIED");
            classifiedEvent.put("partyExternalId", doc.getPartyExternalId() != null
                    ? doc.getPartyExternalId() : "");
            classifiedEvent.put("documentName", doc.getName());
            classifiedEvent.put("uploadedBy", doc.getUploadedByEmail() != null
                    ? doc.getUploadedByEmail() : "");
            rabbitTemplate.convertAndSend(RabbitMqConfig.EXCHANGE, "document.classified", classifiedEvent);
            log.info("Published document.classified for documentId={}", doc.getId());
        } catch (Exception e) {
            log.warn("Failed to publish document.classified for documentId={}: {}", doc.getId(), e.getMessage());
        }
    }

    private void publishWorkflowTrigger(Document doc) {
        try {
            Map<String, Object> workflowEvent = Map.of(
                    "documentId", doc.getId().toString(),
                    "documentName", doc.getName(),
                    "categoryId", doc.getCategoryId(),
                    "uploadedBy", doc.getUploadedByEmail() != null ? doc.getUploadedByEmail() : "system",
                    "partyExternalId", doc.getPartyExternalId() != null ? doc.getPartyExternalId() : "",
                    "correlationId", UUID.randomUUID().toString()
            );
            rabbitTemplate.convertAndSend(
                    RabbitMqConfig.EXCHANGE, RabbitMqConfig.WORKFLOW_TRIGGER_ROUTING_KEY, workflowEvent);
            log.info("Workflow trigger published for documentId={}", doc.getId());
        } catch (Exception e) {
            log.warn("Failed to publish workflow trigger for documentId={}: {}", doc.getId(), e.getMessage());
        }
    }
}
