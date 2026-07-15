package com.ecm.batch.service;

import com.ecm.batch.client.DocumentServiceClient;
import com.ecm.batch.client.EformsServiceClient;
import com.ecm.batch.entity.BatchItem;
import com.ecm.batch.entity.BatchItemStatus;
import com.ecm.batch.messaging.BatchItemMessage;
import com.ecm.batch.repository.BatchItemRepository;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrates batch item processing.
 *
 * <p>Classification logic is delegated to ecm-ocr's pipeline. This service:</p>
 * <ol>
 *   <li>Handles QR code fast-path (eForms with embedded category + customer)</li>
 *   <li>Polls ecm-document for OCR pipeline results (which include classification)</li>
 *   <li>Reads classification results from the document (set by OCR pipeline)</li>
 *   <li>Decides: auto-file or send to review queue</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BatchProcessingService {

    private final BatchItemRepository batchItemRepository;
    private final BatchJobService batchJobService;
    private final QrCodeDetectorService qrCodeDetectorService;
    private final DocumentServiceClient documentServiceClient;
    private final EformsServiceClient eformsServiceClient;
    private final MinioClient minioClient;

    @Value("${ecm.batch.confidence-threshold:90.0}")
    private double confidenceThreshold;

    @Transactional
    public void processItem(BatchItemMessage message) {
        UUID itemId = message.itemId();
        log.info("Processing batch item {}", itemId);

        BatchItem item = batchItemRepository.findById(itemId)
                .orElseThrow(() -> new IllegalStateException("Batch item not found: " + itemId));

        item.setStatus(BatchItemStatus.PROCESSING);
        batchItemRepository.save(item);

        try {
            // Step 1: QR code detection (only if storage key available).
            // Two shapes: eForms-generated JSON ({"ecm":true,"fk":...,"pid":...}) resolves
            // category via a lookup on the form key and takes the party id straight from
            // "pid" if present; legacy manual QRs embed categoryId/customerId directly.
            // A blank-form print has no "pid" (no customer known at print time) — category
            // still fast-paths, customer falls through to normal OCR matching below.
            Integer qrCategoryId = null;
            String qrPartyExternalId = null;
            String qrCaseId = null;
            String qrChecklistItemId = null;
            String storageKey = message.storageKey();
            if (storageKey != null && !storageKey.isBlank()) {
                try {
                    byte[] fileBytes = downloadFile(message.storageBucket(), storageKey);
                    Optional<Map<String, String>> qrData = qrCodeDetectorService.detect(fileBytes);
                    if (qrData.isPresent()) {
                        Map<String, String> qr = qrData.get();
                        log.info("QR code detected for item {}: {}", itemId, qr);

                        if ("true".equalsIgnoreCase(qr.get("ecm")) && qr.get("fk") != null) {
                            qrCategoryId = eformsServiceClient.getDocumentCategoryIdForForm(qr.get("fk"));
                            qrPartyExternalId = qr.get("pid");
                            // Only meaningful together — a case-checklist row is keyed by both.
                            if (qr.get("cid") != null && qr.get("cki") != null) {
                                qrCaseId = qr.get("cid");
                                qrChecklistItemId = qr.get("cki");
                            }
                        } else if (qr.containsKey("categoryId")) {
                            try {
                                qrCategoryId = Integer.parseInt(qr.get("categoryId"));
                            } catch (NumberFormatException e) {
                                log.warn("QR categoryId not numeric for item {}: {}", itemId, qr.get("categoryId"));
                            }
                            qrPartyExternalId = qr.get("customerId");
                        }
                    }
                } catch (Exception e) {
                    log.debug("QR detection skipped for item {}: {}", itemId, e.getMessage());
                }
            }

            if (qrCategoryId != null) {
                item.setDetectedCategoryId(qrCategoryId);
                item.setCategoryConfidence(new BigDecimal("99.00"));
                item.setFinalCategoryId(qrCategoryId);
                setDetectedCustomerIfUuid(item, qrPartyExternalId, new BigDecimal("99.00"));

                if (qrPartyExternalId != null) {
                    // QR resolved both category and party — fast-file, skip OCR entirely.
                    setFinalCustomerIfUuid(item, qrPartyExternalId);
                    item.setStatus(BatchItemStatus.AUTO_FILED);
                    log.info("Item {} QR fast-path auto-filed: category={}, party={}",
                            itemId, qrCategoryId, qrPartyExternalId);

                    updateDocumentMetadata(message.documentId(), qrCategoryId, qrPartyExternalId,
                            "QR_CODE", new BigDecimal("99.00"), qrCaseId, qrChecklistItemId);

                    batchItemRepository.save(item);
                    batchJobService.updateProgress(message.batchId(), item.getStatus());
                    return;
                }
                log.info("Item {} QR fast-path resolved category={} only (no party in QR) — " +
                        "falling through to OCR for customer match", itemId, qrCategoryId);
            }

            // Step 2: Poll ecm-document for OCR + classification results.
            // The OCR pipeline (ecm-ocr) handles text extraction, classification,
            // and customer matching. We just read the results.
            Map<String, Object> doc = pollForOcrResults(message.documentId());

            // If QR already resolved the category, keep it — don't let a lower-confidence
            // OCR classification override a 99%-confidence QR match.
            Integer classifiedCategoryId = qrCategoryId != null ? qrCategoryId : extractInt(doc, "categoryId");
            BigDecimal classificationConfidence = qrCategoryId != null
                    ? new BigDecimal("99.00") : extractDecimal(doc, "classificationConfidence");
            String partyExternalId = extractString(doc, "partyExternalId");
            BigDecimal customerConfidence = partyExternalId != null ? new BigDecimal("80.00") : BigDecimal.ZERO;

            item.setDetectedCategoryId(classifiedCategoryId);
            item.setCategoryConfidence(classificationConfidence);
            if (partyExternalId != null) {
                setDetectedCustomerIfUuid(item, partyExternalId, customerConfidence);
            }

            // Step 4: Decision — auto-file or review
            boolean highCat = classificationConfidence != null
                    && classificationConfidence.doubleValue() >= confidenceThreshold;
            boolean highCust = customerConfidence.doubleValue() >= confidenceThreshold;

            if (highCat && highCust) {
                item.setStatus(BatchItemStatus.AUTO_FILED);
                item.setFinalCategoryId(classifiedCategoryId);
                if (partyExternalId != null) {
                    setFinalCustomerIfUuid(item, partyExternalId);
                }
                log.info("Item {} auto-filed: category={}, customer={}", itemId,
                        classifiedCategoryId, partyExternalId);
            } else {
                item.setStatus(BatchItemStatus.IN_REVIEW);
                log.info("Item {} sent to review: catConf={}, custConf={}", itemId,
                        classificationConfidence, customerConfidence);
            }

            batchItemRepository.save(item);
            batchJobService.updateProgress(message.batchId(), item.getStatus());

        } catch (Exception e) {
            log.error("Failed to process batch item {}: {}", itemId, e.getMessage(), e);
            item.setStatus(BatchItemStatus.FAILED);
            item.setErrorMessage(e.getMessage());
            batchItemRepository.save(item);
            batchJobService.updateProgress(message.batchId(), BatchItemStatus.FAILED);
        }
    }

    /**
     * Poll ecm-document for OCR completion (up to 3 attempts with 2s delay).
     * Returns the document data map or empty map if OCR never completes.
     */
    private Map<String, Object> pollForOcrResults(UUID documentId) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            Map<String, Object> doc = documentServiceClient.getDocument(documentId);
            if (doc == null) continue;

            Object data = doc.get("data");
            if (data instanceof Map<?, ?> dataMap) {
                Boolean ocrCompleted = (Boolean) dataMap.get("ocrCompleted");
                if (Boolean.TRUE.equals(ocrCompleted)) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> result = (Map<String, Object>) data;
                    return result;
                }
            }

            if (attempt < 3) {
                log.debug("OCR not yet complete for document {}, poll attempt {}/3", documentId, attempt);
                try { Thread.sleep(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }
        log.warn("OCR not complete after 3 polls for document {} — proceeding with empty data", documentId);
        return Map.of();
    }

    private void updateDocumentMetadata(UUID documentId, Integer categoryId, String partyExternalId,
                                         String classificationSource, BigDecimal confidence) {
        updateDocumentMetadata(documentId, categoryId, partyExternalId, classificationSource, confidence, null, null);
    }

    /**
     * caseId/checklistItemId are optional — only present when the QR carried a case
     * context (cid/cki). ecm-document only UPDATEs an existing case_documents row for
     * that case+item; it never creates one. No match (e.g. the checklist item was
     * already fulfilled, or ids don't line up) leaves the document filed but
     * unattached to any case — manual assignment, same as a QR with no case context.
     */
    private void updateDocumentMetadata(UUID documentId, Integer categoryId, String partyExternalId,
                                         String classificationSource, BigDecimal confidence,
                                         String caseId, String checklistItemId) {
        try {
            Map<String, Object> metadata = new HashMap<>();
            if (categoryId != null) metadata.put("categoryId", categoryId);
            if (partyExternalId != null) metadata.put("partyExternalId", partyExternalId);
            metadata.put("classificationSource", classificationSource);
            if (confidence != null) metadata.put("classificationConfidence", confidence);
            if (caseId != null && checklistItemId != null) {
                metadata.put("caseId", caseId);
                try {
                    metadata.put("checklistItemId", Integer.parseInt(checklistItemId));
                } catch (NumberFormatException e) {
                    log.warn("QR checklistItemId not numeric for document {}: {}", documentId, checklistItemId);
                }
            }
            documentServiceClient.updateDocumentMetadata(documentId, metadata);
        } catch (Exception e) {
            log.warn("Failed to update document metadata for {}: {}", documentId, e.getMessage());
        }
    }

    /**
     * BatchItem.detectedCustomerId/finalCustomerId are UUID-typed columns, but party
     * identifiers (partyExternalId, whether from OCR customer-matching or a QR "pid")
     * are business identifiers (account numbers, party codes) — not guaranteed to be
     * UUID-shaped. Best-effort parse, matching the pre-existing behavior of the normal
     * OCR customer-matching path: if it's not a UUID, these bookkeeping columns are
     * left unset (the document's own partyExternalId metadata is still set correctly
     * either way, via updateDocumentMetadata — this limitation is scoped to ecm-batch's
     * own confidence-tracking columns, not the actual document linkage).
     */
    private void setDetectedCustomerIfUuid(BatchItem item, String partyExternalId, BigDecimal confidence) {
        try {
            item.setDetectedCustomerId(UUID.fromString(partyExternalId));
            item.setCustomerConfidence(confidence);
        } catch (IllegalArgumentException e) {
            log.debug("partyExternalId is not a UUID, leaving BatchItem.detectedCustomerId unset: {}", partyExternalId);
        }
    }

    private void setFinalCustomerIfUuid(BatchItem item, String partyExternalId) {
        try {
            item.setFinalCustomerId(UUID.fromString(partyExternalId));
        } catch (IllegalArgumentException ignored) {
            // see setDetectedCustomerIfUuid
        }
    }

    private byte[] downloadFile(String bucket, String key) {
        try {
            return minioClient.getObject(
                    GetObjectArgs.builder().bucket(bucket).object(key).build()
            ).readAllBytes();
        } catch (Exception e) {
            throw new RuntimeException("Failed to download file from MinIO: " + key, e);
        }
    }

    private static Integer extractInt(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val == null) return null;
        if (val instanceof Integer i) return i;
        try { return Integer.parseInt(val.toString()); } catch (NumberFormatException e) { return null; }
    }

    private static BigDecimal extractDecimal(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val == null) return null;
        if (val instanceof BigDecimal bd) return bd;
        try { return new BigDecimal(val.toString()); } catch (NumberFormatException e) { return null; }
    }

    private static String extractString(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : null;
    }
}
