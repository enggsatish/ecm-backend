package com.ecm.document.mapper;

import com.ecm.document.dto.DocumentResponse;
import com.ecm.document.entity.Document;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;

/**
 * MapStruct mapper: Document entity → DocumentResponse DTO.
 *
 * ── Sprint-C (unchanged) ──────────────────────────────────────────────────────
 *
 *  segmentId / productLineId auto-map by name (entity field → DTO record parameter).
 *  segmentName / productLineName / categoryName: ignored (no entity column; resolved
 *  later by HierarchyClient).
 *
 * ── Sprint-D additions ────────────────────────────────────────────────────────
 *
 *  extractedFields
 *    Present on the Document entity (extracted_fields jsonb → String).
 *    Added to DocumentResponse record. Auto-maps by name — explicit @Mapping for clarity.
 *
 *  downloadUrl
 *    Not an entity field — synthesised as "/api/documents/{id}/download".
 *    Uses MapStruct expression= which works with Java records (no Builder needed).
 *    DO NOT use @AfterMapping for records — records have no .Builder class.
 *
 *  partyExternalId
 *    Not yet on the Document entity (V6 migration adds the column).
 *    Kept as ignore=true until that migration runs.
 *    Once V6 is applied: remove the ignore line — field will auto-map by name.
 */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface DocumentMapper {

    // Explicit for clarity — same name on both sides
    @Mapping(source = "uploadedByEmail", target = "uploadedByEmail")

    // Sprint-D: extractedFields is now in DocumentResponse; auto-maps by name
    @Mapping(source = "extractedFields", target = "extractedFields")

    // Sprint-D: downloadUrl synthesised from document ID via MapStruct expression.
    // expression= works with Java records. @AfterMapping does NOT (no Builder).
    @Mapping(
            target = "downloadUrl",
            expression = "java(document.getId() != null ? \"/api/documents/\" + document.getId() + \"/download\" : null)"
    )

    // Sprint-C: denormalised name fields — no entity column, resolved post-mapping
    @Mapping(target = "segmentName",     ignore = true)
    @Mapping(target = "productLineName", ignore = true)
    @Mapping(target = "categoryName",    ignore = true)

    // Sprint-D: partyExternalId — entity column added by V6 migration.

    DocumentResponse toResponse(Document document);
}