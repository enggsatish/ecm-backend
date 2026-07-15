package com.ecm.admin.controller;

import com.ecm.admin.dto.CategoryDto;
import com.ecm.admin.dto.PartyDto;
import com.ecm.admin.service.DocumentCategoryService;
import com.ecm.admin.service.HierarchyService;
import com.ecm.admin.service.PartyService;
import com.ecm.common.model.ApiResponse;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Internal-only endpoints for service-to-service calls (ecm-ocr, ecm-batch).
 *
 * These endpoints have NO {@code @PreAuthorize} — access is controlled entirely
 * by AdminSecurityConfig which permits {@code /internal/**} only when the
 * {@code X-Internal-Service} header matches the whitelist.
 *
 * <p>Read-only operations only. No mutations exposed internally.</p>
 *
 * @see com.ecm.admin.config.AdminSecurityConfig
 * @see com.ecm.common.client.AdminServiceClient
 */
@RestController
@RequestMapping("/internal/admin")
public class InternalAdminController {

    private final DocumentCategoryService categoryService;
    private final PartyService partyService;
    private final JdbcTemplate jdbc;

    public InternalAdminController(DocumentCategoryService categoryService,
                                   PartyService partyService,
                                   JdbcTemplate jdbc) {
        this.categoryService = categoryService;
        this.partyService = partyService;
        this.jdbc = jdbc;
    }

    @GetMapping("/categories")
    public ResponseEntity<ApiResponse<List<CategoryDto>>> categories(
            @RequestParam(defaultValue = "true") boolean flat) {
        return ResponseEntity.ok(ApiResponse.ok(flat ? categoryService.listFlat() : categoryService.listTree()));
    }

    @GetMapping("/customers")
    public ResponseEntity<ApiResponse<Page<PartyDto>>> searchCustomers(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(partyService.search(q, page, size)));
    }

    /**
     * Resolve hierarchy (segment, product line) from a category ID.
     * Walks: category → product_document_types → product → product_line → segment.
     * Returns the first match (a category may belong to multiple products).
     *
     * @param categoryId document category ID
     * @return { segmentId, productLineId, segmentName, productLineName } or empty if no mapping
     */
    // ─── OCR Pipeline Config Endpoints (service-to-service) ──────────────────

    @GetMapping("/field-mappings")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getFieldMappings(
            @RequestParam(required = false) String categoryCode) {
        List<Map<String, Object>> rows;
        if (categoryCode != null) {
            rows = jdbc.queryForList(
                    "SELECT * FROM ecm_admin.field_mappings WHERE category_code = ? ORDER BY raw_name",
                    categoryCode);
        } else {
            rows = jdbc.queryForList(
                    "SELECT * FROM ecm_admin.field_mappings ORDER BY category_code, raw_name");
        }
        return ResponseEntity.ok(ApiResponse.ok(rows));
    }

    @GetMapping("/extraction-templates")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getExtractionTemplates(
            @RequestParam(required = false) String categoryCode) {
        List<Map<String, Object>> rows;
        if (categoryCode != null) {
            rows = jdbc.queryForList(
                    "SELECT * FROM ecm_admin.extraction_templates WHERE category_code = ? ORDER BY display_order",
                    categoryCode);
        } else {
            rows = jdbc.queryForList(
                    "SELECT * FROM ecm_admin.extraction_templates ORDER BY category_code, display_order");
        }
        return ResponseEntity.ok(ApiResponse.ok(rows));
    }

    @GetMapping("/confidence-config")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getConfidenceConfig(
            @RequestParam(required = false) String categoryCode) {
        List<Map<String, Object>> rows;
        if (categoryCode != null) {
            rows = jdbc.queryForList(
                    "SELECT * FROM ecm_admin.category_confidence_config WHERE category_code = ?",
                    categoryCode);
        } else {
            rows = jdbc.queryForList(
                    "SELECT * FROM ecm_admin.category_confidence_config ORDER BY category_code");
        }
        return ResponseEntity.ok(ApiResponse.ok(rows));
    }

    @GetMapping("/training-examples")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getTrainingExamples(
            @RequestParam(required = false) String categoryCode,
            @RequestParam(required = false) String status) {
        StringBuilder sql = new StringBuilder("SELECT * FROM ecm_admin.training_examples WHERE 1=1");
        List<Object> params = new java.util.ArrayList<>();

        if (categoryCode != null) {
            sql.append(" AND category_code = ?");
            params.add(categoryCode);
        }
        if (status != null) {
            sql.append(" AND status = ?");
            params.add(status);
        }
        sql.append(" ORDER BY created_at DESC");

        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), params.toArray());
        return ResponseEntity.ok(ApiResponse.ok(rows));
    }

    // ─── Hierarchy Resolution ─────────────────────────────────────────────────

    @GetMapping("/hierarchy/resolve")
    public ResponseEntity<ApiResponse<Map<String, Object>>> resolveHierarchy(
            @RequestParam Integer categoryId) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT p.segment_id, p.product_line_id,
                       s.name AS segment_name, pl.name AS product_line_name
                FROM ecm_admin.product_document_types pdt
                JOIN ecm_admin.products p ON p.id = pdt.product_id
                LEFT JOIN ecm_admin.segments s ON s.id = p.segment_id
                LEFT JOIN ecm_admin.product_lines pl ON pl.id = p.product_line_id
                WHERE pdt.category_id = ? AND pdt.is_active = true AND p.is_active = true
                ORDER BY p.id ASC
                LIMIT 1
                """, categoryId);

            if (rows.isEmpty()) {
                return ResponseEntity.ok(ApiResponse.ok(Map.of()));
            }
            return ResponseEntity.ok(ApiResponse.ok(rows.get(0)));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.ok(Map.of()));
        }
    }
}
