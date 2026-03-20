package com.ecm.admin.service;

import com.ecm.admin.dto.CaseDto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * Case (loan application / account opening) lifecycle management.
 *
 * Cases live in ecm_core schema — writes via JdbcTemplate (cross-schema rule).
 * On creation, auto-populates the document checklist from product_document_types.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CaseService {

    private final JdbcTemplate jdbc;

    // ── List ─────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<CaseResponse> list(String status, UUID partyId) {
        StringBuilder sql = new StringBuilder("""
            SELECT c.id, c.external_ref, c.party_id, c.product_id, c.case_type,
                   c.status, c.assigned_to, c.assigned_to_name, c.source_system,
                   c.source_ref, c.process_instance_id, c.opened_at, c.completed_at, c.created_at,
                   p.display_name AS party_name, p.external_id AS party_ext_id,
                   pr.display_name AS product_name
            FROM ecm_core.cases c
            LEFT JOIN ecm_core.parties p ON p.id = c.party_id
            LEFT JOIN ecm_admin.products pr ON pr.id = c.product_id
            WHERE 1=1
        """);
        List<Object> params = new ArrayList<>();

        if (status != null && !status.isBlank()) {
            sql.append(" AND c.status = ?");
            params.add(status);
        }
        if (partyId != null) {
            sql.append(" AND c.party_id = ?");
            params.add(partyId);
        }
        sql.append(" ORDER BY c.created_at DESC LIMIT 100");

        return jdbc.query(sql.toString(), (rs, rowNum) -> new CaseResponse(
                UUID.fromString(rs.getString("id")),
                rs.getString("external_ref"),
                rs.getObject("party_id") != null ? UUID.fromString(rs.getString("party_id")) : null,
                rs.getString("party_name"),
                rs.getString("party_ext_id"),
                rs.getObject("product_id") != null ? rs.getInt("product_id") : null,
                rs.getString("product_name"),
                rs.getString("case_type"),
                rs.getString("status"),
                rs.getString("assigned_to"),
                rs.getString("assigned_to_name"),
                rs.getString("source_system"),
                rs.getString("source_ref"),
                rs.getString("process_instance_id"),
                null, // metadata not loaded in list view
                rs.getObject("opened_at", OffsetDateTime.class),
                rs.getObject("completed_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class),
                null // checklist loaded separately
        ), params.toArray());
    }

    // ── Get by ID (with checklist) ───────────────────────────────────────────

    @Transactional(readOnly = true)
    public CaseResponse getById(UUID caseId) {
        List<CaseResponse> cases = jdbc.query("""
            SELECT c.id, c.external_ref, c.party_id, c.product_id, c.case_type,
                   c.status, c.assigned_to, c.assigned_to_name, c.source_system,
                   c.source_ref, c.process_instance_id, c.metadata,
                   c.opened_at, c.completed_at, c.created_at,
                   p.display_name AS party_name, p.external_id AS party_ext_id,
                   pr.display_name AS product_name
            FROM ecm_core.cases c
            LEFT JOIN ecm_core.parties p ON p.id = c.party_id
            LEFT JOIN ecm_admin.products pr ON pr.id = c.product_id
            WHERE c.id = ?
        """, (rs, rowNum) -> new CaseResponse(
                UUID.fromString(rs.getString("id")),
                rs.getString("external_ref"),
                rs.getObject("party_id") != null ? UUID.fromString(rs.getString("party_id")) : null,
                rs.getString("party_name"),
                rs.getString("party_ext_id"),
                rs.getObject("product_id") != null ? rs.getInt("product_id") : null,
                rs.getString("product_name"),
                rs.getString("case_type"),
                rs.getString("status"),
                rs.getString("assigned_to"),
                rs.getString("assigned_to_name"),
                rs.getString("source_system"),
                rs.getString("source_ref"),
                rs.getString("process_instance_id"),
                parseMetadata(rs.getString("metadata")),
                rs.getObject("opened_at", OffsetDateTime.class),
                rs.getObject("completed_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class),
                null
        ), caseId);

        if (cases.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Case not found: " + caseId);
        }

        CaseResponse c = cases.get(0);
        List<ChecklistItem> checklist = getChecklist(caseId);
        return new CaseResponse(
                c.id(), c.externalRef(), c.partyId(), c.partyDisplayName(), c.partyExternalId(),
                c.productId(), c.productName(), c.caseType(), c.status(),
                c.assignedTo(), c.assignedToName(), c.sourceSystem(), c.sourceRef(),
                c.processInstanceId(), c.metadata(),
                c.openedAt(), c.completedAt(), c.createdAt(),
                checklist
        );
    }

    // ── Create ───────────────────────────────────────────────────────────────

    @Transactional
    public CaseResponse create(CreateCaseRequest req) {
        UUID caseId = UUID.randomUUID();

        jdbc.update("""
            INSERT INTO ecm_core.cases
                (id, external_ref, party_id, product_id, case_type, status,
                 assigned_to, assigned_to_name, source_system, source_ref)
            VALUES (?, ?, ?, ?, ?, 'OPEN', ?, ?, ?, ?)
            """,
                caseId,
                req.externalRef(),
                req.partyId(),
                req.productId(),
                req.caseType() != null ? req.caseType() : "GENERAL",
                req.assignedTo(),
                req.assignedToName(),
                req.sourceSystem() != null ? req.sourceSystem() : "ECM",
                req.sourceRef()
        );

        // Auto-populate checklist from product_document_types
        int items = jdbc.update("""
            INSERT INTO ecm_core.case_documents (case_id, product_document_type_id, status)
            SELECT ?, pdt.id, 'PENDING'
            FROM ecm_admin.product_document_types pdt
            WHERE pdt.product_id = ? AND pdt.is_active = true
            ORDER BY pdt.sort_order
            """, caseId, req.productId());

        log.info("Case created: id={}, product={}, checklist={} items", caseId, req.productId(), items);
        return getById(caseId);
    }

    // ── Update Status ────────────────────────────────────────────────────────

    @Transactional
    public CaseResponse updateStatus(UUID caseId, UpdateCaseStatusRequest req) {
        int rows = jdbc.update("""
            UPDATE ecm_core.cases
            SET status = ?, updated_at = NOW(),
                completed_at = CASE WHEN ? IN ('COMPLETED', 'REJECTED', 'CANCELLED') THEN NOW() ELSE completed_at END
            WHERE id = ?
            """, req.status(), req.status(), caseId);

        if (rows == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Case not found");
        log.info("Case status updated: id={}, status={}", caseId, req.status());
        return getById(caseId);
    }

    // ── Checklist Operations ─────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ChecklistItem> getChecklist(UUID caseId) {
        return jdbc.query("""
            SELECT cd.id, cd.product_document_type_id, cd.document_id, cd.status,
                   pdt.name AS doc_type_name, pdt.code AS doc_type_code,
                   pdt.source_type, pdt.is_required, pdt.category_id,
                   pdt.form_definition_id,
                   fd.form_key AS form_key,
                   d.name AS doc_name
            FROM ecm_core.case_documents cd
            JOIN ecm_admin.product_document_types pdt ON pdt.id = cd.product_document_type_id
            LEFT JOIN ecm_forms.form_definitions fd ON fd.id = pdt.form_definition_id
            LEFT JOIN ecm_core.documents d ON d.id = cd.document_id
            WHERE cd.case_id = ?
            ORDER BY pdt.sort_order, pdt.id
            """, (rs, rowNum) -> new ChecklistItem(
                rs.getInt("id"),
                rs.getInt("product_document_type_id"),
                rs.getString("doc_type_name"),
                rs.getString("doc_type_code"),
                rs.getString("source_type"),
                rs.getBoolean("is_required"),
                rs.getObject("category_id") != null ? rs.getInt("category_id") : null,
                rs.getObject("form_definition_id") != null ? UUID.fromString(rs.getString("form_definition_id")) : null,
                rs.getString("form_key"),
                rs.getObject("document_id") != null ? UUID.fromString(rs.getString("document_id")) : null,
                rs.getString("doc_name"),
                rs.getString("status")
        ), caseId);
    }

    /** Link an uploaded document to a checklist item */
    @Transactional
    public CaseResponse linkDocument(UUID caseId, UploadChecklistDocumentRequest req, String uploadedBy) {
        jdbc.update("""
            UPDATE ecm_core.case_documents
            SET document_id = ?, status = 'UPLOADED', uploaded_by = ?, uploaded_at = NOW(), updated_at = NOW()
            WHERE id = ? AND case_id = ?
            """, req.documentId(), uploadedBy, req.checklistItemId(), caseId);

        log.info("Checklist document linked: caseId={}, itemId={}, docId={}",
                caseId, req.checklistItemId(), req.documentId());
        return getById(caseId);
    }

    /** Waive a required document (admin override) */
    @Transactional
    public CaseResponse waiveItem(UUID caseId, Integer itemId, WaiveChecklistItemRequest req, String waivedBy) {
        jdbc.update("""
            UPDATE ecm_core.case_documents
            SET status = 'WAIVED', waived_by = ?, waived_reason = ?, updated_at = NOW()
            WHERE id = ? AND case_id = ?
            """, waivedBy, req.reason(), itemId, caseId);

        log.info("Checklist item waived: caseId={}, itemId={}", caseId, itemId);
        return getById(caseId);
    }

    /** Add a note to the case activity log — stored in metadata JSONB */
    @Transactional
    public CaseResponse addNote(UUID caseId, AddNoteRequest req, String author) {
        String timestamp = java.time.OffsetDateTime.now().toString();
        String noteJson = String.format(
                "{\"note\":\"%s\",\"author\":\"%s\",\"timestamp\":\"%s\"}",
                req.note().replace("\"", "\\\""),
                author.replace("\"", "\\\""),
                timestamp);

        // First ensure metadata is not null
        jdbc.update("UPDATE ecm_core.cases SET metadata = '{}'::jsonb WHERE id = ? AND metadata IS NULL", caseId);

        // Then append to notes array
        jdbc.update("""
            UPDATE ecm_core.cases
            SET metadata = jsonb_set(
                    metadata,
                    '{notes}',
                    COALESCE(metadata->'notes', '[]'::jsonb) || ?::jsonb
                ),
                updated_at = NOW()
            WHERE id = ?
            """, noteJson, caseId);

        log.info("Note added to case: id={}, author={}", caseId, author);
        return getById(caseId);
    }

    /** Cancel a case — admin can cancel at any stage except COMPLETED */
    @Transactional
    public void cancel(UUID caseId) {
        int rows = jdbc.update("""
            UPDATE ecm_core.cases
            SET status = 'CANCELLED', completed_at = NOW(), updated_at = NOW()
            WHERE id = ? AND status NOT IN ('COMPLETED', 'CANCELLED')
            """, caseId);
        if (rows == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Case not found or already completed/cancelled");
        log.info("Case cancelled: id={}", caseId);
    }

    /** Delete a case — only OPEN cases with no linked documents */
    @Transactional
    public void delete(UUID caseId) {
        Integer docCount = jdbc.queryForObject(
                "SELECT count(*) FROM ecm_core.case_documents WHERE case_id = ? AND document_id IS NOT NULL",
                Integer.class, caseId);
        if (docCount != null && docCount > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cannot delete case with linked documents. Cancel it instead.");
        }
        jdbc.update("DELETE FROM ecm_core.case_documents WHERE case_id = ?", caseId);
        int rows = jdbc.update("DELETE FROM ecm_core.cases WHERE id = ?", caseId);
        if (rows == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Case not found");
        log.info("Case deleted: id={}", caseId);
    }

    /** Parse JSONB metadata string into a Map for JSON serialization */
    private Object parseMetadata(String metadataStr) {
        if (metadataStr == null || metadataStr.isBlank()) return null;
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(metadataStr, Map.class);
        } catch (Exception e) {
            log.debug("Failed to parse metadata: {}", e.getMessage());
            return null;
        }
    }
}
