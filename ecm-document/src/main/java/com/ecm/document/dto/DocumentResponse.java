package com.ecm.document.dto;

import com.ecm.document.entity.DocumentStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Outbound DTO — never exposes blob_storage_path or internal IDs directly.
 *
 * Sprint-C additions:
 *   segmentId       — stored segment ID for filtering
 *   segmentName     — denormalised for UI breadcrumb display
 *   productLineId   — stored product line ID for filtering
 *   productLineName — denormalised for UI breadcrumb display
 */
public record DocumentResponse(
        UUID    id,
        String  name,
        String  originalFilename,
        String  mimeType,
        Long    fileSizeBytes,
        Integer categoryId,
        String  categoryName,           // denormalised (resolved from admin)
        Integer departmentId,
        String  uploadedByEmail,
        DocumentStatus status,
        Integer version,
        UUID    parentDocId,
        Boolean isLatestVersion,
        Boolean ocrCompleted,
        String  extractedText,
        String[] tags,
        Instant createdAt,
        Instant updatedAt,

        // ── Sprint-C: hierarchy context ──────────────────────────────────────
        Integer segmentId,
        String  segmentName,            // resolved from ecm_admin at query time
        Integer productLineId,
        String  productLineName         // resolved from ecm_admin at query time
) {}