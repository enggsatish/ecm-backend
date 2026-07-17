package com.ecm.batch.service;

import com.ecm.batch.client.DocumentServiceClient;
import com.ecm.batch.config.RabbitConfig;
import com.ecm.batch.dto.BatchItemResponse;
import com.ecm.batch.dto.BatchJobResponse;
import com.ecm.batch.dto.BatchStatsResponse;
import com.ecm.batch.entity.*;
import com.ecm.batch.messaging.BatchItemMessage;
import com.ecm.batch.messaging.BatchItemProducer;
import com.ecm.batch.repository.BatchItemRepository;
import com.ecm.batch.repository.BatchJobRepository;
import com.ecm.common.client.DocumentPromotionClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class BatchJobService {

    private final BatchJobRepository      batchJobRepository;
    private final BatchItemRepository     batchItemRepository;
    private final BatchItemProducer       batchItemProducer;
    private final DocumentPromotionClient promotionClient;
    private final DocumentServiceClient   documentServiceClient;
    private final RabbitTemplate          rabbitTemplate;

    @Value("${ecm.storage.bucket:ecm-documents}")
    private String storageBucket;

    @Transactional
    public BatchJobResponse createBatch(List<MultipartFile> files, String source, String notes, String createdBy) {
        UUID jobId = UUID.randomUUID();
        BatchJob job = BatchJob.builder()
                .id(jobId)
                .status(BatchJobStatus.QUEUED)
                .source(source != null ? source : "MANUAL_UPLOAD")
                .totalItems(files.size())
                .notes(notes)
                .createdBy(createdBy)
                .build();
        batchJobRepository.save(job);

        for (MultipartFile file : files) {
            UUID itemId = UUID.randomUUID();
            String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "unknown";
            // Display name: strip extension + timestamp (e.g., "invoice - 2026-03-29 14:38")
            String baseName = filename.contains(".") ? filename.substring(0, filename.lastIndexOf('.')) : filename;
            String displayName = baseName + " - " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));

            try {
                // 1. Promote file to ecm-document (MinIO upload + Document record + OCR trigger)
                UUID documentId = promotionClient.promote(
                        file.getBytes(), filename, displayName,
                        createdBy, null, null,  // no party, no category — auto-classify later
                        false   // real scanned file — needs full OCR/classification
                );

                if (documentId == null) {
                    log.error("Promotion returned null for file '{}' in batch {}", filename, jobId);
                    BatchItem failedItem = BatchItem.builder()
                            .id(itemId)
                            .batchJob(job)
                            .originalFilename(filename)
                            .status(BatchItemStatus.FAILED)
                            .errorMessage("Document promotion failed — ecm-document returned null")
                            .build();
                    batchItemRepository.save(failedItem);
                    continue;
                }

                // 2. Get document details for storage info
                String docBucket = storageBucket;
                String docStorageKey = "";
                try {
                    Map<String, Object> docResponse = documentServiceClient.getDocument(documentId);
                    // ApiResponse envelope: { success, data: { ... }, message }
                    Object data = docResponse.get("data");
                    if (data instanceof Map<?, ?> dataMap) {
                        Object bucket = dataMap.get("storageBucket");
                        Object key = dataMap.get("storageKey");
                        if (bucket != null) docBucket = bucket.toString();
                        if (key != null) docStorageKey = key.toString();
                    }
                } catch (Exception e) {
                    log.warn("Could not fetch document details for {} — using defaults: {}",
                            documentId, e.getMessage());
                }

                // 3. Save batch item with real documentId
                BatchItem item = BatchItem.builder()
                        .id(itemId)
                        .batchJob(job)
                        .documentId(documentId)
                        .originalFilename(filename)
                        .status(BatchItemStatus.QUEUED)
                        .build();
                batchItemRepository.save(item);

                // 4. Publish processing message with real storage coordinates
                batchItemProducer.sendProcessMessage(new BatchItemMessage(
                        jobId, itemId, documentId, docBucket, docStorageKey, filename
                ));

            } catch (Exception e) {
                log.error("Failed to process file '{}' in batch {}: {}", filename, jobId, e.getMessage(), e);
                BatchItem failedItem = BatchItem.builder()
                        .id(itemId)
                        .batchJob(job)
                        .originalFilename(filename)
                        .status(BatchItemStatus.FAILED)
                        .errorMessage("Upload failed: " + e.getMessage())
                        .build();
                batchItemRepository.save(failedItem);
            }
        }

        job.setStatus(BatchJobStatus.PROCESSING);
        batchJobRepository.save(job);

        log.info("Created batch job {} with {} items", jobId, files.size());
        return toResponse(job);
    }

    @Transactional(readOnly = true)
    public BatchJobResponse getBatch(UUID jobId) {
        BatchJob job = batchJobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Batch job not found: " + jobId));
        return toResponse(job);
    }

    /** Returns the raw entity — for internal service use (counter adjustments). */
    @Transactional
    public BatchJob getBatchJobEntity(UUID jobId) {
        return batchJobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Batch job not found: " + jobId));
    }

    @Transactional(readOnly = true)
    public Page<BatchJobResponse> listBatches(Pageable pageable) {
        return batchJobRepository.findAllByOrderByCreatedAtDesc(pageable)
                .map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public Page<BatchItemResponse> getJobItems(UUID jobId, Pageable pageable) {
        return batchItemRepository.findByBatchJobId(jobId, pageable)
                .map(this::toItemResponse);
    }

    @Transactional
    public void updateProgress(UUID jobId, BatchItemStatus itemStatus) {
        BatchJob job = batchJobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Batch job not found: " + jobId));

        job.setProcessedItems(job.getProcessedItems() + 1);
        switch (itemStatus) {
            case AUTO_FILED -> job.setAutoFiled(job.getAutoFiled() + 1);
            case IN_REVIEW -> job.setSentToReview(job.getSentToReview() + 1);
            case FAILED -> job.setFailedItems(job.getFailedItems() + 1);
            default -> { /* no counter update for other statuses */ }
        }

        if (job.getProcessedItems() >= job.getTotalItems()) {
            completeBatch(job);
        }

        batchJobRepository.save(job);
    }

    @Transactional(readOnly = true)
    public BatchStatsResponse getStats() {
        long totalJobs = batchJobRepository.count();
        List<BatchJob> activeJobs = batchJobRepository.findByStatusIn(
                List.of(BatchJobStatus.QUEUED, BatchJobStatus.PROCESSING));
        long totalAutoFiled = batchItemRepository.findByStatus(BatchItemStatus.AUTO_FILED).size();
        long totalInReview = batchItemRepository.findByStatus(BatchItemStatus.IN_REVIEW).size();
        long totalFailed = batchItemRepository.findByStatus(BatchItemStatus.FAILED).size();
        long processedToday = batchItemRepository.count(); // total processed items

        return new BatchStatsResponse(
                processedToday,
                totalAutoFiled,
                totalInReview,
                totalFailed,
                totalJobs,
                activeJobs.size()
        );
    }

    @Transactional(readOnly = true)
    public Page<BatchItemResponse> getAutoProcessedItems(Pageable pageable) {
        return batchItemRepository.findByStatus(BatchItemStatus.AUTO_FILED, pageable)
                .map(this::toItemResponse);
    }

    private void completeBatch(BatchJob job) {
        if (job.getFailedItems() > 0) {
            job.setStatus(BatchJobStatus.COMPLETED_WITH_ERRORS);
        } else {
            job.setStatus(BatchJobStatus.COMPLETED);
        }
        job.setCompletedAt(Instant.now());
        log.info("Batch job {} completed. auto={}, review={}, failed={}",
                job.getId(), job.getAutoFiled(), job.getSentToReview(), job.getFailedItems());

        publishBatchNotification(job);
    }

    private void publishBatchNotification(BatchJob job) {
        try {
            int successCount = job.getAutoFiled() + job.getSentToReview();
            Map<String, Object> event = Map.of(
                    "batchId", job.getId().toString(),
                    "batchName", job.getNotes() != null ? job.getNotes() : "Batch job",
                    "createdBy", job.getCreatedBy() != null ? job.getCreatedBy() : "",
                    "totalCount", job.getTotalItems(),
                    "successCount", successCount,
                    "failedCount", job.getFailedItems()
            );

            if (job.getFailedItems() > 0 && successCount == 0) {
                // Entire batch failed
                rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE, "batch.failed", event);
                log.debug("Published batch.failed for batchId={}", job.getId());
            } else {
                // Completed (possibly with some errors)
                rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE, "batch.completed", event);
                log.debug("Published batch.completed for batchId={}", job.getId());
            }
        } catch (Exception e) {
            log.warn("Failed to publish batch notification for batchId={}: {}", job.getId(), e.getMessage());
        }
    }

    private BatchJobResponse toResponse(BatchJob job) {
        return new BatchJobResponse(
                job.getId(),
                job.getStatus().name(),
                job.getSource(),
                job.getTotalItems(),
                job.getProcessedItems(),
                job.getAutoFiled(),
                job.getSentToReview(),
                job.getFailedItems(),
                job.getNotes(),
                job.getCreatedBy(),
                job.getCreatedAt(),
                job.getCompletedAt()
        );
    }

    private BatchItemResponse toItemResponse(BatchItem item) {
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
