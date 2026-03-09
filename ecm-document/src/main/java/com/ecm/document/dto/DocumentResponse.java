package com.ecm.document.dto;

import com.ecm.document.entity.DocumentStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Outbound DTO — never exposes blob_storage_path or internal IDs directly.
 *
 * Sprint-C additions:
 *   segmentId / segmentName / productLineId / productLineName — hierarchy breadcrumb
 *
 * Sprint-D additions (minimal — only what the frontend actually needs):
 *   extractedFields  — structured OCR output JSON; was on the entity but missing here,
 *                      so DocumentViewerModal "Extracted Fields" tab was always empty.
 *   downloadUrl      — synthesised by DocumentMapper as "/api/documents/{id}/download";
 *                      DocumentViewerModal needs this to build the blob fetch URL.
 *   partyExternalId  — soft ref to ecm_core.parties.external_id; restored for upload
 *                      context (was dropped by Sprint-C hierarchy changes).
 *                      Mapper ignores it until V6 Flyway migration adds the DB column.
 *
 * IMPORTANT: This is a Java record. MapStruct maps it by matching constructor parameter
 * names to source field names. Every field listed here must either:
 *   a) auto-map by name from the Document entity, OR
 *   b) have an explicit @Mapping on DocumentMapper (ignore=true or expression=...).
 * Never use @AfterMapping with a record — records have no Builder class.
 */
public record DocumentResponse(
        UUID    id,
        String  name,
        String  originalFilename,
        String  mimeType,
        Long    fileSizeBytes,
        Integer categoryId,
        String  categoryName,           // denormalised (resolved from admin) — ignored in mapper
        Integer departmentId,
        String  uploadedByEmail,
        DocumentStatus status,
        Integer version,
        UUID    parentDocId,
        Boolean isLatestVersion,
        Boolean ocrCompleted,
        String  extractedText,
        String  extractedFields,        // ← Sprint-D: was on entity, now exposed in DTO
        String[] tags,
        Instant createdAt,
        Instant updatedAt,

        // ── Sprint-C: hierarchy context ──────────────────────────────────────
        Integer segmentId,
        String  segmentName,            // ignored in mapper (no entity column)
        Integer productLineId,
        String  productLineName,        // ignored in mapper (no entity column)

        // ── Sprint-D: viewer + party context ────────────────────────────────
        String  downloadUrl,            // synthesised by mapper via expression
        String  partyExternalId         // ignored in mapper until V6 migration runs
) {}