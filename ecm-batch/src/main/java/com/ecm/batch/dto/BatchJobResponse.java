package com.ecm.batch.dto;

import java.time.Instant;
import java.util.UUID;

public record BatchJobResponse(
        UUID id,
        String status,
        String source,
        int totalItems,
        int processedItems,
        int autoFiled,
        int sentToReview,
        int failedItems,
        String notes,
        String createdBy,
        Instant createdAt,
        Instant completedAt
) {}
