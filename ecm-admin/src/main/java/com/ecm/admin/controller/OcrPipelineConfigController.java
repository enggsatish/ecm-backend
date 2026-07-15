package com.ecm.admin.controller;

import com.ecm.common.model.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Admin UI endpoints for managing OCR pipeline configuration:
 * field mappings, extraction templates, confidence config, and training examples.
 *
 * <p>All endpoints require {@code admin:configure} permission.</p>
 */
@RestController
@RequestMapping("/api/admin/ocr-pipeline")
@PreAuthorize("hasPermission(null, 'admin:configure')")
public class OcrPipelineConfigController {

    private final JdbcTemplate jdbc;

    public OcrPipelineConfigController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ─── Field Mappings ───────────────────────────────────────────────────────

    @GetMapping("/field-mappings")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listFieldMappings(
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

    @PostMapping("/field-mappings")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createFieldMapping(@RequestBody Map<String, Object> body) {
        jdbc.update(
                "INSERT INTO ecm_admin.field_mappings (category_code, raw_name, canonical_name, field_type) " +
                "VALUES (?, ?, ?, ?)",
                body.get("categoryCode"), body.get("rawName"),
                body.get("canonicalName"), body.getOrDefault("fieldType", "STRING"));

        List<Map<String, Object>> created = jdbc.queryForList(
                "SELECT * FROM ecm_admin.field_mappings WHERE category_code = ? AND raw_name = ?",
                body.get("categoryCode"), body.get("rawName"));

        return ResponseEntity.ok(ApiResponse.ok(created.isEmpty() ? Map.of() : created.get(0), "Field mapping created"));
    }

    @PutMapping("/field-mappings/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateFieldMapping(
            @PathVariable Integer id, @RequestBody Map<String, Object> body) {
        jdbc.update(
                "UPDATE ecm_admin.field_mappings SET category_code = ?, raw_name = ?, " +
                "canonical_name = ?, field_type = ? WHERE id = ?",
                body.get("categoryCode"), body.get("rawName"),
                body.get("canonicalName"), body.getOrDefault("fieldType", "STRING"), id);

        List<Map<String, Object>> updated = jdbc.queryForList(
                "SELECT * FROM ecm_admin.field_mappings WHERE id = ?", id);

        return ResponseEntity.ok(ApiResponse.ok(updated.isEmpty() ? Map.of() : updated.get(0), "Field mapping updated"));
    }

    @DeleteMapping("/field-mappings/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteFieldMapping(@PathVariable Integer id) {
        jdbc.update("DELETE FROM ecm_admin.field_mappings WHERE id = ?", id);
        return ResponseEntity.ok(ApiResponse.ok(null, "Field mapping deleted"));
    }

    // ─── Extraction Templates ─────────────────────────────────────────────────

    @GetMapping("/extraction-templates")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listExtractionTemplates(
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

    @PostMapping("/extraction-templates")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createExtractionTemplate(@RequestBody Map<String, Object> body) {
        jdbc.update(
                "INSERT INTO ecm_admin.extraction_templates " +
                "(category_code, field_name, field_type, required, display_order, description) " +
                "VALUES (?, ?, ?, ?, ?, ?)",
                body.get("categoryCode"), body.get("fieldName"),
                body.getOrDefault("fieldType", "STRING"),
                body.getOrDefault("required", false),
                body.getOrDefault("displayOrder", 0),
                body.get("description"));

        List<Map<String, Object>> created = jdbc.queryForList(
                "SELECT * FROM ecm_admin.extraction_templates WHERE category_code = ? AND field_name = ?",
                body.get("categoryCode"), body.get("fieldName"));

        return ResponseEntity.ok(ApiResponse.ok(created.isEmpty() ? Map.of() : created.get(0), "Extraction template created"));
    }

    @PutMapping("/extraction-templates/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateExtractionTemplate(
            @PathVariable Integer id, @RequestBody Map<String, Object> body) {
        jdbc.update(
                "UPDATE ecm_admin.extraction_templates SET category_code = ?, field_name = ?, " +
                "field_type = ?, required = ?, display_order = ?, description = ?, updated_at = NOW() WHERE id = ?",
                body.get("categoryCode"), body.get("fieldName"),
                body.getOrDefault("fieldType", "STRING"),
                body.getOrDefault("required", false),
                body.getOrDefault("displayOrder", 0),
                body.get("description"), id);

        List<Map<String, Object>> updated = jdbc.queryForList(
                "SELECT * FROM ecm_admin.extraction_templates WHERE id = ?", id);

        return ResponseEntity.ok(ApiResponse.ok(updated.isEmpty() ? Map.of() : updated.get(0), "Extraction template updated"));
    }

    @DeleteMapping("/extraction-templates/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteExtractionTemplate(@PathVariable Integer id) {
        jdbc.update("DELETE FROM ecm_admin.extraction_templates WHERE id = ?", id);
        return ResponseEntity.ok(ApiResponse.ok(null, "Extraction template deleted"));
    }

    // ─── Confidence Config ────────────────────────────────────────────────────

    @GetMapping("/confidence-config")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listConfidenceConfig() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM ecm_admin.category_confidence_config ORDER BY category_code");
        return ResponseEntity.ok(ApiResponse.ok(rows));
    }

    @PutMapping("/confidence-config/{categoryCode}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateConfidenceConfig(
            @PathVariable String categoryCode, @RequestBody Map<String, Object> body) {
        // Upsert: update if exists, insert if not
        int updated = jdbc.update(
                "UPDATE ecm_admin.category_confidence_config SET " +
                "auto_accept_threshold = ?, review_threshold = ?, reject_threshold = ?, " +
                "weight_llm_confidence = ?, weight_field_match = ?, " +
                "weight_keyword_score = ?, weight_training_sim = ?, updated_at = NOW() " +
                "WHERE category_code = ?",
                body.getOrDefault("autoAcceptThreshold", 85.00),
                body.getOrDefault("reviewThreshold", 50.00),
                body.getOrDefault("rejectThreshold", 20.00),
                body.getOrDefault("weightLlmConfidence", 0.30),
                body.getOrDefault("weightFieldMatch", 0.35),
                body.getOrDefault("weightKeywordScore", 0.20),
                body.getOrDefault("weightTrainingSim", 0.15),
                categoryCode);

        if (updated == 0) {
            jdbc.update(
                    "INSERT INTO ecm_admin.category_confidence_config " +
                    "(category_code, auto_accept_threshold, review_threshold, reject_threshold, " +
                    "weight_llm_confidence, weight_field_match, weight_keyword_score, weight_training_sim) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    categoryCode,
                    body.getOrDefault("autoAcceptThreshold", 85.00),
                    body.getOrDefault("reviewThreshold", 50.00),
                    body.getOrDefault("rejectThreshold", 20.00),
                    body.getOrDefault("weightLlmConfidence", 0.30),
                    body.getOrDefault("weightFieldMatch", 0.35),
                    body.getOrDefault("weightKeywordScore", 0.20),
                    body.getOrDefault("weightTrainingSim", 0.15));
        }

        List<Map<String, Object>> result = jdbc.queryForList(
                "SELECT * FROM ecm_admin.category_confidence_config WHERE category_code = ?",
                categoryCode);

        return ResponseEntity.ok(ApiResponse.ok(result.isEmpty() ? Map.of() : result.get(0), "Confidence config updated"));
    }

    // ─── Training Examples ────────────────────────────────────────────────────

    @GetMapping("/training-examples")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listTrainingExamples(
            @RequestParam(required = false) String categoryCode,
            @RequestParam(required = false) String status) {
        StringBuilder sql = new StringBuilder("SELECT * FROM ecm_admin.training_examples WHERE 1=1");
        List<Object> params = new ArrayList<>();

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

    @PutMapping("/training-examples/{id}/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateTrainingExampleStatus(
            @PathVariable Long id, @RequestBody Map<String, Object> body) {
        String newStatus = (String) body.get("status");
        if (newStatus == null || !List.of("CANDIDATE", "VERIFIED", "ACTIVE", "RETIRED").contains(newStatus)) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("Invalid status. Must be one of: CANDIDATE, VERIFIED, ACTIVE, RETIRED", "INVALID_STATUS"));
        }

        // If verifying/activating, record who verified
        if ("VERIFIED".equals(newStatus) || "ACTIVE".equals(newStatus)) {
            jdbc.update(
                    "UPDATE ecm_admin.training_examples SET status = ?, " +
                    "verified_by = ?, verified_at = NOW() WHERE id = ?",
                    newStatus, body.getOrDefault("verifiedBy", "admin"), id);
        } else {
            jdbc.update(
                    "UPDATE ecm_admin.training_examples SET status = ? WHERE id = ?",
                    newStatus, id);
        }

        List<Map<String, Object>> updated = jdbc.queryForList(
                "SELECT * FROM ecm_admin.training_examples WHERE id = ?", id);

        return ResponseEntity.ok(ApiResponse.ok(updated.isEmpty() ? Map.of() : updated.get(0), "Training example status updated"));
    }

    @DeleteMapping("/training-examples/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteTrainingExample(@PathVariable Long id) {
        jdbc.update("DELETE FROM ecm_admin.training_examples WHERE id = ?", id);
        return ResponseEntity.ok(ApiResponse.ok(null, "Training example deleted"));
    }
}
