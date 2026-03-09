package com.ecm.document.dto;

import jakarta.validation.constraints.Size;

/**
 * Optional metadata accompanying a multipart upload.
 * The actual file comes through @RequestPart("file").
 * uploadedBy is resolved from the JWT in the controller — not supplied by the client.
 *
 * Sprint-C additions:
 *   segmentId / productLineId / segmentCode / productLineCode — hierarchy context
 *
 * Sprint-D fix:
 *   partyExternalId — soft reference to the party (customer / organisation) the document
 *   belongs to. This was previously in the upload form but was dropped during Sprint-C's
 *   hierarchy changes. Restored here.
 *
 *   partyExternalId is a STRING (not an integer FK) because:
 *   - ecm_core.parties.external_id is the stable identifier coming from an external
 *     CRM/core system (e.g. a loan origination system customer ID).
 *   - The document module does not own the party entity. It stores the external_id as
 *     a soft reference only — no FK constraint. Looking up the display name for the
 *     task queue uses a JdbcTemplate cross-schema read (EcmTaskService.enrichTask).
 */
public record DocumentUploadRequest(
        @Size(max = 500) String name,          // display name; defaults to original filename
        Integer categoryId,
        Integer departmentId,
        @Size(max = 50)  String status,        // optional override; defaults to PENDING_OCR
        String   metadata,                     // arbitrary JSON string
        String[] tags,

        // ── Sprint-C: hierarchy context ───────────────────────────────────────
        Integer segmentId,                     // soft ref → ecm_admin.segments.id
        Integer productLineId,                 // soft ref → ecm_admin.product_lines.id
        @Size(max = 20)  String segmentCode,   // e.g. RETAIL — used in MinIO path
        @Size(max = 30)  String productLineCode, // e.g. RETAIL_LOANS — used in MinIO path

        // ── Sprint-D: party context ───────────────────────────────────────────
        @Size(max = 100) String partyExternalId  // soft ref → ecm_core.parties.external_id
) {}
