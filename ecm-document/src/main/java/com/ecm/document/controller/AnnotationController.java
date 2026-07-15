package com.ecm.document.controller;

import com.ecm.common.model.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * Document annotation endpoints — PDF pin-based comments for case review.
 *
 * Annotations are tied to a document + case context. When viewing a document
 * outside a case (Documents page), annotations are read-only.
 *
 * Uses JdbcTemplate for ecm_core.document_annotations (cross-schema pattern).
 */
@Slf4j
@RestController
@RequestMapping("/api/documents/{documentId}/annotations")
@RequiredArgsConstructor
public class AnnotationController {

    private final JdbcTemplate jdbc;

    // ── List annotations ────────────────────────────────────────────────────

    @GetMapping
    @PreAuthorize("hasPermission(null, 'documents:read')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list(
            @PathVariable UUID documentId,
            @RequestParam(required = false) UUID caseId) {

        String sql;
        Object[] params;

        if (caseId != null) {
            // Annotations for this document in this specific case
            sql = """
                SELECT a.*,
                       (SELECT COUNT(*) FROM ecm_core.document_annotations r WHERE r.parent_id = a.id) as reply_count
                FROM ecm_core.document_annotations a
                WHERE a.document_id = ? AND a.case_id = ? AND a.parent_id IS NULL
                ORDER BY a.page_number, a.y_percent, a.created_at
                """;
            params = new Object[]{documentId, caseId};
        } else {
            // All annotations for this document across all cases
            sql = """
                SELECT a.*,
                       (SELECT COUNT(*) FROM ecm_core.document_annotations r WHERE r.parent_id = a.id) as reply_count
                FROM ecm_core.document_annotations a
                WHERE a.document_id = ? AND a.parent_id IS NULL
                ORDER BY a.page_number, a.y_percent, a.created_at
                """;
            params = new Object[]{documentId};
        }

        List<Map<String, Object>> annotations = jdbc.queryForList(sql, params);
        return ResponseEntity.ok(ApiResponse.ok(annotations));
    }

    // ── Get replies for an annotation ────────────────────────────────────────

    @GetMapping("/{annotationId}/replies")
    @PreAuthorize("hasPermission(null, 'documents:read')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getReplies(
            @PathVariable UUID documentId,
            @PathVariable UUID annotationId) {

        List<Map<String, Object>> replies = jdbc.queryForList("""
            SELECT * FROM ecm_core.document_annotations
            WHERE parent_id = ? AND document_id = ?
            ORDER BY created_at ASC
            """, annotationId, documentId);

        return ResponseEntity.ok(ApiResponse.ok(replies));
    }

    // ── Create annotation ───────────────────────────────────────────────────

    @PostMapping
    @PreAuthorize("hasPermission(null, 'documents:read')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(
            @PathVariable UUID documentId,
            @RequestBody CreateAnnotationRequest req,
            @AuthenticationPrincipal Jwt jwt) {

        String email = jwt.getClaimAsString("email");
        String name = jwt.getClaimAsString("name");
        if (name == null) name = email != null ? email.split("@")[0] : "Unknown";

        if (req.caseId == null) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("Annotations can only be added in case context", "CASE_REQUIRED"));
        }

        // Verify case is active
        try {
            String caseStatus = jdbc.queryForObject(
                    "SELECT status FROM ecm_core.cases WHERE id = ?",
                    String.class, req.caseId);
            if (List.of("COMPLETED", "CANCELLED", "REJECTED").contains(caseStatus)) {
                return ResponseEntity.badRequest()
                        .body(ApiResponse.error("Cannot annotate — case is " + caseStatus, "CASE_CLOSED"));
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("Case not found: " + req.caseId, "CASE_NOT_FOUND"));
        }

        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO ecm_core.document_annotations
                (id, document_id, case_id, page_number, x_percent, y_percent,
                 comment, author_email, author_name, parent_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
                id, documentId, req.caseId, req.pageNumber,
                req.xPercent, req.yPercent, req.comment,
                email, name, req.parentId);

        log.info("Annotation created: id={}, doc={}, case={}, page={}, by={}",
                id, documentId, req.caseId, req.pageNumber, email);

        Map<String, Object> created = jdbc.queryForMap(
                "SELECT * FROM ecm_core.document_annotations WHERE id = ?", id);

        return ResponseEntity.ok(ApiResponse.ok(created, "Annotation added"));
    }

    // ── Resolve / Unresolve ─────────────────────────────────────────────────

    @PutMapping("/{annotationId}/resolve")
    @PreAuthorize("hasPermission(null, 'documents:read')")
    public ResponseEntity<ApiResponse<Void>> resolve(
            @PathVariable UUID documentId,
            @PathVariable UUID annotationId,
            @AuthenticationPrincipal Jwt jwt) {

        String email = jwt.getClaimAsString("email");

        jdbc.update("""
            UPDATE ecm_core.document_annotations
            SET resolved = true, resolved_by = ?, resolved_at = NOW(), updated_at = NOW()
            WHERE id = ? AND document_id = ?
            """, email, annotationId, documentId);

        log.info("Annotation resolved: id={}, by={}", annotationId, email);
        return ResponseEntity.ok(ApiResponse.ok(null, "Annotation resolved"));
    }

    @PutMapping("/{annotationId}/unresolve")
    @PreAuthorize("hasPermission(null, 'documents:read')")
    public ResponseEntity<ApiResponse<Void>> unresolve(
            @PathVariable UUID documentId,
            @PathVariable UUID annotationId) {

        jdbc.update("""
            UPDATE ecm_core.document_annotations
            SET resolved = false, resolved_by = NULL, resolved_at = NULL, updated_at = NOW()
            WHERE id = ? AND document_id = ?
            """, annotationId, documentId);

        return ResponseEntity.ok(ApiResponse.ok(null, "Annotation reopened"));
    }

    // ── Delete (own annotations only) ────────────────────────────────────────

    @DeleteMapping("/{annotationId}")
    @PreAuthorize("hasPermission(null, 'documents:read')")
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable UUID documentId,
            @PathVariable UUID annotationId,
            @AuthenticationPrincipal Jwt jwt) {

        String email = jwt.getClaimAsString("email");

        // Delete replies first, then the annotation
        jdbc.update("DELETE FROM ecm_core.document_annotations WHERE parent_id = ?", annotationId);
        int rows = jdbc.update("""
            DELETE FROM ecm_core.document_annotations
            WHERE id = ? AND document_id = ? AND author_email = ?
            """, annotationId, documentId, email);

        if (rows == 0) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("Annotation not found or not owned by you", "NOT_FOUND"));
        }

        return ResponseEntity.ok(ApiResponse.ok(null, "Annotation deleted"));
    }

    // ── Request DTO ─────────────────────────────────────────────────────────

    public record CreateAnnotationRequest(
            UUID caseId,
            int pageNumber,
            double xPercent,
            double yPercent,
            String comment,
            UUID parentId    // null for top-level, set for replies
    ) {}
}
