package com.ecm.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * All request/response DTOs for the Segment / ProductLine hierarchy.
 * Using Java records keeps the code compact and immutable.
 */
public class HierarchyDtos {

    // ── Segment ───────────────────────────────────────────────────────────────

    /** Outbound: segment summary. */
    public record SegmentDto(
            Integer id,
            String  name,
            String  code,
            String  description,
            Boolean isActive,
            OffsetDateTime createdAt
    ) {}

    /** Inbound: create or update a segment. */
    public record SegmentRequest(
            @NotBlank String name,
            @NotBlank String code,
            String  description,
            Boolean active          // null = leave unchanged on update
    ) {}

    // ── Product Line ──────────────────────────────────────────────────────────

    /** Outbound: product line with denormalised segment name. */
    public record ProductLineDto(
            Integer id,
            Integer segmentId,
            String  segmentName,    // denormalised for UI convenience
            String  name,
            String  code,
            String  description,
            Boolean isActive,
            OffsetDateTime createdAt
    ) {}

    /** Inbound: create or update a product line. */
    public record ProductLineRequest(
            @NotNull  Integer segmentId,
            @NotBlank String  name,
            @NotBlank String  code,
            String  description,
            Boolean active
    ) {}

    // ── Full Hierarchy Tree ───────────────────────────────────────────────────

    /**
     * Full hierarchy tree returned by GET /api/admin/hierarchy.
     * Each segment contains its product lines, each product line contains its products.
     * Used by the document upload form cascading selects.
     */
    public record HierarchyNode(
            Integer segmentId,
            String  segmentName,
            String  segmentCode,
            List<ProductLineNode> productLines
    ) {}

    public record ProductLineNode(
            Integer id,
            String  name,
            String  code,
            List<ProductSummary> products
    ) {}

    /**
     * Lightweight product summary embedded in the hierarchy tree.
     * Avoids the heavyweight ProductDto (which carries categoryLinks / productSchema).
     */
    public record ProductSummary(
            Integer id,
            String  productCode,
            String  displayName,
            String  description,
            Boolean isActive
    ) {}
}