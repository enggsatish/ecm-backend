package com.ecm.batch.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record BatchItemResponse(
        UUID id,
        UUID batchId,
        UUID documentId,
        String originalFilename,
        String status,
        Integer detectedCategoryId,
        BigDecimal categoryConfidence,
        UUID detectedCustomerId,
        BigDecimal customerConfidence,
        String extractedName,
        String extractedAccountNo,
        LocalDate extractedDob,
        String extractedAddress,
        String candidateCustomerIds,
        String reviewedBy,
        Instant reviewedAt,
        Integer finalCategoryId,
        UUID finalCustomerId,
        String reviewNotes,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt
) {}
