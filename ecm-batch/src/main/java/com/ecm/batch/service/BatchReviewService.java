package com.ecm.batch.service;

import com.ecm.batch.client.DocumentServiceClient;
import com.ecm.batch.dto.BatchItemResponse;
import com.ecm.batch.dto.ReviewRequest;
import com.ecm.batch.entity.BatchItem;
import com.ecm.batch.entity.BatchItemStatus;
import com.ecm.batch.entity.BatchJobStatus;
import com.ecm.batch.messaging.BatchItemMessage;
import com.ecm.batch.messaging.BatchItemProducer;
import com.ecm.batch.repository.BatchItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class BatchReviewService {

    private final BatchItemRepository batchItemRepository;
    private final BatchJobService batchJobService;
    private final DocumentServiceClient documentServiceClient;
    private final BatchProcessingService batchProcessingService;
    private final BatchItemProducer batchItemProducer;

    @Transactional(readOnly = true)
    public Page<BatchItemResponse> getReviewQueue(Pageable pageable) {
        return batchItemRepository.findByStatus(BatchItemStatus.IN_REVIEW, pageable)
                .map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public BatchItemResponse getReviewItem(UUID itemId) {
        BatchItem item = batchItemRepository.findById(itemId)
                .orElseThrow(() -> new IllegalArgumentException("Batch item not found: " + itemId));
        return toResponse(item);
    }

    /**
     * Approve/classify a batch item — works for both IN_REVIEW and FAILED items.
     * Sets final category + customer, updates document metadata in ecm-document,
     * and adjusts the parent batch job counters.
     */
    @Transactional
    public BatchItemResponse approveItem(UUID itemId, ReviewRequest request, String reviewedBy) {
        BatchItem item = batchItemRepository.findById(itemId)
                .orElseThrow(() -> new IllegalArgumentException("Batch item not found: " + itemId));

        BatchItemStatus previousStatus = item.getStatus();
        if (previousStatus != BatchItemStatus.IN_REVIEW && previousStatus != BatchItemStatus.FAILED) {
            throw new IllegalStateException("Item cannot be classified in status: " + previousStatus);
        }

        item.setFinalCategoryId(request.finalCategoryId());
        item.setFinalCustomerId(request.finalCustomerId());
        item.setReviewNotes(request.reviewNotes());
        item.setReviewedBy(reviewedBy);
        item.setReviewedAt(Instant.now());
        item.setStatus(BatchItemStatus.REVIEW_COMPLETE);
        item.setErrorMessage(null); // clear any previous error

        batchItemRepository.save(item);

        // Update document metadata in ecm-document with reviewed classification
        if (item.getDocumentId() != null) {
            try {
                Map<String, Object> metadata = new HashMap<>();
                if (request.finalCategoryId() != null) metadata.put("categoryId", request.finalCategoryId());
                // Use the party's external business ID (customerRef), not the internal UUID
                if (request.partyExternalId() != null && !request.partyExternalId().isBlank()) {
                    metadata.put("partyExternalId", request.partyExternalId());
                } else if (request.finalCustomerId() != null) {
                    // Fallback: use UUID string if no externalId provided
                    metadata.put("partyExternalId", request.finalCustomerId().toString());
                }
                metadata.put("classificationSource", "MANUAL");
                documentServiceClient.updateDocumentMetadata(item.getDocumentId(), metadata);
            } catch (Exception e) {
                log.warn("Failed to update document metadata for {} after review approval: {}",
                        item.getDocumentId(), e.getMessage());
            }
        }

        // Adjust parent batch job counters
        adjustBatchJobCounters(item.getBatchJob().getId(), previousStatus, BatchItemStatus.REVIEW_COMPLETE);

        log.info("Batch item {} approved by {}. category={}, customer={} (was {})",
                itemId, reviewedBy, request.finalCategoryId(), request.finalCustomerId(), previousStatus);

        return toResponse(item);
    }

    /**
     * Retry a failed batch item — re-queues it through the processing pipeline.
     * First checks if the document was already classified (reconcile), and if so
     * just syncs the status without reprocessing.
     */
    @Transactional
    public BatchItemResponse retryItem(UUID itemId, String retriedBy) {
        BatchItem item = batchItemRepository.findById(itemId)
                .orElseThrow(() -> new IllegalArgumentException("Batch item not found: " + itemId));

        if (item.getStatus() != BatchItemStatus.FAILED) {
            throw new IllegalStateException("Only FAILED items can be retried: " + item.getStatus());
        }

        // First try to reconcile — check if document was already classified
        if (item.getDocumentId() != null) {
            try {
                Map<String, Object> doc = documentServiceClient.getDocument(item.getDocumentId());
                Object data = doc.get("data");
                if (data instanceof Map<?, ?> dataMap) {
                    Object catId = dataMap.get("categoryId");
                    Object classSource = dataMap.get("classificationSource");
                    if (catId != null && classSource != null) {
                        // Document already classified — reconcile instead of reprocessing
                        log.info("Reconcile: item {} document {} already classified (category={}, source={})",
                                itemId, item.getDocumentId(), catId, classSource);

                        Integer categoryId = ((Number) catId).intValue();
                        item.setDetectedCategoryId(categoryId);
                        item.setFinalCategoryId(categoryId);
                        item.setCategoryConfidence(new java.math.BigDecimal("95.00"));

                        Object partyExt = dataMap.get("partyExternalId");
                        if (partyExt != null && !partyExt.toString().isBlank()) {
                            try {
                                UUID custId = UUID.fromString(partyExt.toString());
                                item.setDetectedCustomerId(custId);
                                item.setFinalCustomerId(custId);
                                item.setCustomerConfidence(new java.math.BigDecimal("95.00"));
                            } catch (IllegalArgumentException ignored) {}
                        }

                        BatchItemStatus previousStatus = item.getStatus();
                        item.setStatus(BatchItemStatus.AUTO_FILED);
                        item.setErrorMessage(null);
                        item.setReviewedBy(retriedBy);
                        item.setReviewedAt(Instant.now());
                        item.setReviewNotes("Reconciled — document was already classified via " + classSource);
                        batchItemRepository.save(item);
                        adjustBatchJobCounters(item.getBatchJob().getId(), previousStatus, BatchItemStatus.AUTO_FILED);
                        return toResponse(item);
                    }
                }
            } catch (Exception e) {
                log.debug("Could not reconcile item {} with document service: {}", itemId, e.getMessage());
            }
        }

        // Document not yet classified — re-queue for processing
        BatchItemStatus previousStatus = item.getStatus();
        item.setStatus(BatchItemStatus.QUEUED);
        item.setErrorMessage(null);
        batchItemRepository.save(item);
        adjustBatchJobCounters(item.getBatchJob().getId(), previousStatus, BatchItemStatus.QUEUED);

        // Publish processing message
        batchItemProducer.sendProcessMessage(new BatchItemMessage(
                item.getBatchJob().getId(), item.getId(), item.getDocumentId(),
                "ecm-documents", "", item.getOriginalFilename()
        ));

        log.info("Batch item {} retried (re-queued) by {}", itemId, retriedBy);
        return toResponse(item);
    }

    /**
     * Reconcile a single batch item: check the document's current state in
     * ecm-document and sync the batch item status accordingly.
     */
    @Transactional
    public BatchItemResponse reconcileItem(UUID itemId) {
        BatchItem item = batchItemRepository.findById(itemId)
                .orElseThrow(() -> new IllegalArgumentException("Batch item not found: " + itemId));

        if (item.getDocumentId() == null) {
            throw new IllegalStateException("Cannot reconcile item without a documentId");
        }

        Map<String, Object> doc = documentServiceClient.getDocument(item.getDocumentId());
        Object data = doc.get("data");
        if (!(data instanceof Map<?, ?> dataMap)) {
            throw new IllegalStateException("Could not retrieve document " + item.getDocumentId());
        }

        Object catId = dataMap.get("categoryId");
        Object classSource = dataMap.get("classificationSource");
        Object partyExt = dataMap.get("partyExternalId");

        BatchItemStatus previousStatus = item.getStatus();
        boolean hasCategory = catId != null;
        boolean hasCustomer = partyExt != null && !partyExt.toString().isBlank();

        if (hasCategory) {
            item.setDetectedCategoryId(((Number) catId).intValue());
            item.setFinalCategoryId(((Number) catId).intValue());
        }
        if (hasCustomer) {
            try {
                UUID custId = UUID.fromString(partyExt.toString());
                item.setDetectedCustomerId(custId);
                item.setFinalCustomerId(custId);
            } catch (IllegalArgumentException ignored) {}
        }

        if (hasCategory && hasCustomer) {
            item.setStatus(BatchItemStatus.AUTO_FILED);
            item.setReviewNotes("Reconciled from document — source: " + classSource);
        } else if (hasCategory || hasCustomer) {
            item.setStatus(BatchItemStatus.IN_REVIEW);
            item.setReviewNotes("Partially reconciled — " +
                    (hasCategory ? "category ok" : "category missing") + ", " +
                    (hasCustomer ? "customer ok" : "customer missing"));
        }
        // If neither, leave status as-is

        item.setErrorMessage(null);
        batchItemRepository.save(item);

        if (previousStatus != item.getStatus()) {
            adjustBatchJobCounters(item.getBatchJob().getId(), previousStatus, item.getStatus());
        }

        log.info("Reconciled batch item {}: {} -> {} (category={}, customer={})",
                itemId, previousStatus, item.getStatus(), hasCategory, hasCustomer);

        return toResponse(item);
    }

    @Transactional
    public BatchItemResponse flagItem(UUID itemId, String reviewNotes, String reviewedBy) {
        BatchItem item = batchItemRepository.findById(itemId)
                .orElseThrow(() -> new IllegalArgumentException("Batch item not found: " + itemId));

        item.setReviewNotes(reviewNotes);
        item.setReviewedBy(reviewedBy);
        item.setReviewedAt(Instant.now());
        // Keep in IN_REVIEW status but record the flag notes
        batchItemRepository.save(item);

        log.info("Batch item {} flagged by {}: {}", itemId, reviewedBy, reviewNotes);
        return toResponse(item);
    }

    /**
     * Adjusts the parent batch job counters when an item transitions between statuses.
     */
    private void adjustBatchJobCounters(UUID jobId, BatchItemStatus from, BatchItemStatus to) {
        try {
            var job = batchJobService.getBatchJobEntity(jobId);
            // Decrement old counter
            switch (from) {
                case FAILED -> job.setFailedItems(Math.max(0, job.getFailedItems() - 1));
                case IN_REVIEW -> job.setSentToReview(Math.max(0, job.getSentToReview() - 1));
                case AUTO_FILED -> job.setAutoFiled(Math.max(0, job.getAutoFiled() - 1));
                default -> {}
            }
            // Increment new counter
            switch (to) {
                case AUTO_FILED, REVIEW_COMPLETE -> job.setAutoFiled(job.getAutoFiled() + 1);
                case IN_REVIEW -> job.setSentToReview(job.getSentToReview() + 1);
                case FAILED -> job.setFailedItems(job.getFailedItems() + 1);
                default -> {}
            }
            // Re-evaluate job status
            if (job.getFailedItems() == 0 && job.getStatus() == BatchJobStatus.COMPLETED_WITH_ERRORS) {
                job.setStatus(BatchJobStatus.COMPLETED);
            }
        } catch (Exception e) {
            log.warn("Could not adjust batch job counters for job {}: {}", jobId, e.getMessage());
        }
    }

    private BatchItemResponse toResponse(BatchItem item) {
        return new BatchItemResponse(
                item.getId(),
                item.getBatchJob().getId(),
                item.getDocumentId(),
                item.getOriginalFilename(),
                item.getStatus().name(),
                item.getDetectedCategoryId(),
                item.getCategoryConfidence(),
                item.getDetectedCustomerId(),
                item.getCustomerConfidence(),
                item.getExtractedName(),
                item.getExtractedAccountNo(),
                item.getExtractedDob(),
                item.getExtractedAddress(),
                item.getCandidateCustomerIds(),
                item.getReviewedBy(),
                item.getReviewedAt(),
                item.getFinalCategoryId(),
                item.getFinalCustomerId(),
                item.getReviewNotes(),
                item.getErrorMessage(),
                item.getCreatedAt(),
                item.getUpdatedAt()
        );
    }
}
