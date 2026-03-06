package com.ecm.document.mapper;

import com.ecm.document.dto.DocumentResponse;
import com.ecm.document.entity.Document;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;

/**
 * MapStruct mapper: Document entity → DocumentResponse DTO.
 *
 * ── Sprint-C changes ──────────────────────────────────────────────────────────
 *
 *  segmentId / productLineId
 *    Both fields now exist on the Document entity (added by Sprint-C migration
 *    V5__add_segment_context.sql). MapStruct auto-maps them by name — no explicit
 *    @Mapping annotation required.
 *
 *  segmentName / productLineName / categoryName
 *    These are denormalised display-name fields on DocumentResponse that have NO
 *    corresponding column on the Document entity. Without explicit ignore
 *    directives MapStruct throws a compile-time error:
 *       "unmapped target property: segmentName"
 *    They are intentionally null in Sprint-C and will be resolved by the
 *    HierarchyClient (Redis-cached WebClient) wired in Sprint-D.
 *
 * ── Files verdict ─────────────────────────────────────────────────────────────
 *  DocumentMapper.java      → NEEDS UPDATE  (this file — 3 @Mapping ignores added)
 *  DocumentServiceImpl.java → NO CHANGE     (segmentId / productLineId already
 *                                            present in the Document builder,
 *                                            lines 89-90 of the uploaded file)
 */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface DocumentMapper {

    // uploadedByEmail is the same name on both sides; listed explicitly for clarity.
    @Mapping(source = "uploadedByEmail", target = "uploadedByEmail")

    // segmentId and productLineId auto-map by name — no annotation needed here.

    // Sprint-C: denormalised name fields exist on DocumentResponse but NOT on
    // the Document entity. Mark them ignored so MapStruct skips them at compile
    // time. Sprint-D will populate these via HierarchyClient after mapping.
    @Mapping(target = "segmentName",     ignore = true)
    @Mapping(target = "productLineName", ignore = true)
    @Mapping(target = "categoryName",    ignore = true)

    DocumentResponse toResponse(Document document);
}