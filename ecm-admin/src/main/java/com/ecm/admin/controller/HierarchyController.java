package com.ecm.admin.controller;

import com.ecm.admin.dto.HierarchyDtos.*;
import com.ecm.admin.service.HierarchyService;
import com.ecm.common.audit.AuditLog;
import com.ecm.common.model.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class HierarchyController {

    private final HierarchyService service;

    // ── Hierarchy Tree (accessible to all authenticated users — needed by upload form) ──

    /**
     * GET /api/admin/hierarchy
     * Returns the full Segment → ProductLine → Product tree.
     * Intentionally NOT restricted to ECM_ADMIN — all authenticated users need this
     * when uploading documents (cascading selects).
     */
    @GetMapping("/hierarchy")
    public ResponseEntity<ApiResponse<List<HierarchyNode>>> getHierarchy() {
        return ResponseEntity.ok(ApiResponse.ok(service.getFullHierarchy()));
    }

    // ── Segments ───────────────────────────────────────────────────────────────

    /**
     * GET /api/admin/segments
     * Returns all segments (including inactive) for the admin management table.
     */
    @GetMapping("/segments")
    public ResponseEntity<ApiResponse<List<SegmentDto>>> listSegments() {
        return ResponseEntity.ok(ApiResponse.ok(service.listAllSegments()));
    }

    /**
     * POST /api/admin/segments
     * Admin-only: create a new segment.
     */
    @PostMapping("/segments")
    @PreAuthorize("hasRole('ECM_ADMIN')")
    @AuditLog(event = "SEGMENT_CREATED", resourceType = "SEGMENT")
    public ResponseEntity<ApiResponse<SegmentDto>> createSegment(
            @Valid @RequestBody SegmentRequest req) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.ok(service.createSegment(req), "Segment created"));
    }

    /**
     * PUT /api/admin/segments/{id}
     * Admin-only: update name, description, or active flag.
     * Code is immutable after creation.
     */
    @PutMapping("/segments/{id}")
    @PreAuthorize("hasRole('ECM_ADMIN')")
    @AuditLog(event = "SEGMENT_UPDATED", resourceType = "SEGMENT")
    public ResponseEntity<ApiResponse<SegmentDto>> updateSegment(
            @PathVariable Integer id,
            @Valid @RequestBody SegmentRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(service.updateSegment(id, req), "Segment updated"));
    }

    // ── Product Lines ──────────────────────────────────────────────────────────

    /**
     * GET /api/admin/product-lines
     * Optional ?segmentId=N filter. Returns active product lines.
     */
    @GetMapping("/product-lines")
    public ResponseEntity<ApiResponse<List<ProductLineDto>>> listProductLines(
            @RequestParam(required = false) Integer segmentId) {
        List<ProductLineDto> lines = segmentId != null
                ? service.listProductLinesBySegment(segmentId)
                : service.listAllProductLines();
        return ResponseEntity.ok(ApiResponse.ok(lines));
    }

    /**
     * POST /api/admin/product-lines
     * Admin-only: create a new product line under the given segment.
     */
    @PostMapping("/product-lines")
    @PreAuthorize("hasRole('ECM_ADMIN')")
    @AuditLog(event = "PRODUCT_LINE_CREATED", resourceType = "PRODUCT_LINE")
    public ResponseEntity<ApiResponse<ProductLineDto>> createProductLine(
            @Valid @RequestBody ProductLineRequest req) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.ok(service.createProductLine(req), "Product line created"));
    }

    /**
     * PUT /api/admin/product-lines/{id}
     * Admin-only: update name, description, or active flag.
     */
    @PutMapping("/product-lines/{id}")
    @PreAuthorize("hasRole('ECM_ADMIN')")
    @AuditLog(event = "PRODUCT_LINE_UPDATED", resourceType = "PRODUCT_LINE")
    public ResponseEntity<ApiResponse<ProductLineDto>> updateProductLine(
            @PathVariable Integer id,
            @Valid @RequestBody ProductLineRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(service.updateProductLine(id, req), "Product line updated"));
    }
}