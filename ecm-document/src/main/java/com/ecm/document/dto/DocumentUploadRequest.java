package com.ecm.document.dto;

import jakarta.validation.constraints.Size;

/**
 * Optional metadata accompanying a multipart upload.
 * The actual file comes through @RequestPart("file").
 * uploadedBy is resolved from the JWT in the controller — not supplied by the client.
 *
 * Sprint-C additions:
 *   segmentId       — soft ref to ecm_admin.segments.id
 *   productLineId   — soft ref to ecm_admin.product_lines.id
 *   segmentCode     — used to build the MinIO storage path (e.g. RETAIL)
 *   productLineCode — used to build the MinIO storage path (e.g. RETAIL_LOANS)
 *
 * The front-end resolves segment/product line codes from the hierarchy tree
 * returned by GET /api/admin/hierarchy and includes them in the form upload.
 * The document service stores the codes in the MinIO path and the IDs in the DB.
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
        @Size(max = 30)  String productLineCode // e.g. RETAIL_LOANS — used in MinIO path
) {}