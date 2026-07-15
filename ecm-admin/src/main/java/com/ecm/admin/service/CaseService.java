package com.ecm.admin.service;

import com.ecm.admin.dto.CaseDto.*;
import com.ecm.common.model.PagedResult;
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
    private final WorkflowClient workflowClient;
    private final org.springframework.amqp.rabbit.core.RabbitTemplate rabbitTemplate;
    private final com.ecm.common.client.DocumentPromotionClient documentPromotionClient;
    private final ExternalSessionService externalSessionService;

    // ── List ─────────────────────────────────────────────────────────────────

    /** Standard SELECT columns for case queries */
    private static final String CASE_SELECT_COLS = """
            c.id, c.external_ref, c.party_id, c.product_id, c.case_type,
            c.status, c.returned_from_review,
            c.assigned_to, c.assigned_to_name, c.assigned_to_group,
            c.claimed_by, c.claimed_by_name, c.source_system,
            c.source_ref, c.process_instance_id, c.opened_at, c.completed_at, c.created_at,
            p.display_name AS party_name, p.external_id AS party_ext_id,
            pr.display_name AS product_name
        """;

    /** Maps a ResultSet row to CaseResponse (without checklist/metadata) */
    private CaseResponse mapCaseRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new CaseResponse(
                UUID.fromString(rs.getString("id")),
                rs.getString("external_ref"),
                rs.getObject("party_id") != null ? UUID.fromString(rs.getString("party_id")) : null,
                rs.getString("party_name"),
                rs.getString("party_ext_id"),
                rs.getObject("product_id") != null ? rs.getInt("product_id") : null,
                rs.getString("product_name"),
                rs.getString("case_type"),
                rs.getString("status"),
                rs.getObject("returned_from_review") != null ? rs.getBoolean("returned_from_review") : false,
                rs.getString("assigned_to"),
                rs.getString("assigned_to_name"),
                rs.getString("assigned_to_group"),
                rs.getString("claimed_by"),
                rs.getString("claimed_by_name"),
                rs.getString("source_system"),
                rs.getString("source_ref"),
                rs.getString("process_instance_id"),
                null, // metadata loaded separately in getById
                rs.getObject("opened_at", OffsetDateTime.class),
                rs.getObject("completed_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class),
                null  // checklist loaded separately
        );
    }

    @Transactional(readOnly = true)
    public PagedResult<CaseResponse> list(String status, UUID partyId, String search, String caseType,
                                           String assignedTo, String assignedToGroup, Boolean unclaimed,
                                           int page, int size) {
        StringBuilder where = new StringBuilder("""
            FROM ecm_core.cases c
            LEFT JOIN ecm_core.parties p ON p.id = c.party_id
            LEFT JOIN ecm_admin.products pr ON pr.id = c.product_id
            WHERE 1=1
        """);
        List<Object> params = new ArrayList<>();

        if (status != null && !status.isBlank()) {
            where.append(" AND c.status = ?");
            params.add(status);
        }
        if (partyId != null) {
            where.append(" AND c.party_id = ?");
            params.add(partyId);
        }
        if (caseType != null && !caseType.isBlank()) {
            where.append(" AND c.case_type = ?");
            params.add(caseType);
        }
        if (assignedTo != null && !assignedTo.isBlank()) {
            where.append(" AND (c.assigned_to = ? OR c.claimed_by = ?)");
            params.add(assignedTo);
            params.add(assignedTo);
        }
        if (assignedToGroup != null && !assignedToGroup.isBlank()) {
            where.append(" AND c.assigned_to_group = ?");
            params.add(assignedToGroup);
        }
        if (Boolean.TRUE.equals(unclaimed)) {
            where.append(" AND c.assigned_to_group IS NOT NULL AND (c.claimed_by IS NULL OR c.claimed_by = '')");
        }
        if (search != null && !search.isBlank()) {
            where.append(" AND (c.external_ref ILIKE ? OR p.display_name ILIKE ? OR p.external_id ILIKE ? OR pr.display_name ILIKE ?)");
            String like = "%" + search.trim() + "%";
            params.add(like);
            params.add(like);
            params.add(like);
            params.add(like);
        }

        // Count query
        String countSql = "SELECT COUNT(*) " + where;
        long total = jdbc.queryForObject(countSql, Long.class, params.toArray());

        // Data query with pagination
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 100)); // cap at 100
        int offset = safePage * safeSize;

        String dataSql = "SELECT " + CASE_SELECT_COLS + where
                + " ORDER BY c.created_at DESC LIMIT ? OFFSET ?";
        List<Object> dataParams = new ArrayList<>(params);
        dataParams.add(safeSize);
        dataParams.add(offset);

        List<CaseResponse> content = jdbc.query(dataSql, (rs, rowNum) -> mapCaseRow(rs), dataParams.toArray());

        return PagedResult.of(content, safePage, safeSize, total);
    }

    // ── Get by ID (with checklist) ───────────────────────────────────────────

    @Transactional(readOnly = true)
    public CaseResponse getById(UUID caseId) {
        List<CaseResponse> cases = jdbc.query(
            "SELECT " + CASE_SELECT_COLS + ", c.metadata" + """
            FROM ecm_core.cases c
            LEFT JOIN ecm_core.parties p ON p.id = c.party_id
            LEFT JOIN ecm_admin.products pr ON pr.id = c.product_id
            WHERE c.id = ?
        """, (rs, rowNum) -> {
            CaseResponse base = mapCaseRow(rs);
            // Re-create with metadata populated
            return new CaseResponse(
                base.id(), base.externalRef(), base.partyId(), base.partyDisplayName(), base.partyExternalId(),
                base.productId(), base.productName(), base.caseType(), base.status(), base.returnedFromReview(),
                base.assignedTo(), base.assignedToName(), base.assignedToGroup(),
                base.claimedBy(), base.claimedByName(),
                base.sourceSystem(), base.sourceRef(), base.processInstanceId(),
                parseMetadata(rs.getString("metadata")),
                base.openedAt(), base.completedAt(), base.createdAt(),
                null
            );
        }, caseId);

        if (cases.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Case not found: " + caseId);
        }

        CaseResponse c = cases.get(0);
        List<ChecklistItem> checklist = getChecklist(caseId);
        return new CaseResponse(
                c.id(), c.externalRef(), c.partyId(), c.partyDisplayName(), c.partyExternalId(),
                c.productId(), c.productName(), c.caseType(), c.status(), c.returnedFromReview(),
                c.assignedTo(), c.assignedToName(), c.assignedToGroup(),
                c.claimedBy(), c.claimedByName(),
                c.sourceSystem(), c.sourceRef(), c.processInstanceId(), c.metadata(),
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
            VALUES (?, ?, ?, ?, ?, 'NEW', ?, ?, ?, ?)
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

        // Auto-create PENDING enrollment for this product
        if (req.productId() != null) {
            try {
                Integer productLineId = jdbc.queryForObject(
                        "SELECT product_line_id FROM ecm_admin.products WHERE id = ?",
                        Integer.class, req.productId());
                if (productLineId != null) {
                    jdbc.update("""
                        INSERT INTO ecm_core.party_product_enrollments
                            (party_id, product_line_id, product_id, case_id, status, enrolled_by)
                        VALUES (?, ?, ?, ?, 'PENDING', 'system')
                        ON CONFLICT (party_id, product_line_id, product_id) DO UPDATE
                            SET case_id = EXCLUDED.case_id, status = 'PENDING'
                        """, req.partyId(), productLineId, req.productId(), caseId);
                    log.info("PENDING enrollment created: caseId={}, productId={}", caseId, req.productId());
                }
            } catch (Exception e) {
                log.warn("Failed to create pending enrollment: {}", e.getMessage());
            }
        }

        log.info("Case created: id={}, product={}, checklist={} items", caseId, req.productId(), items);
        recordTimelineEvent(caseId, "CASE_CREATED",
                "Case created for product: " + req.productId(), null, "system");
        return getById(caseId);
    }

    // ── Update Status ────────────────────────────────────────────────────────

    @Transactional
    public CaseResponse updateStatus(UUID caseId, UpdateCaseStatusRequest req,
                                     String callerSub, String callerEmail) {
        // Enforce: if case is assigned to a group, the caller must have claimed it
        // (or be the direct assignee).
        // Exceptions:
        //   - "Pick Up for Review" (UNDER_REVIEW) auto-claims like "Start Working"
        //   - Admins can always act
        boolean isPickUp = "UNDER_REVIEW".equals(req.status());
        boolean isClaimTransition = "IN_PROGRESS".equals(req.status()) || isPickUp;

        try {
            var caseRow = jdbc.queryForMap(
                    "SELECT assigned_to, assigned_to_group, claimed_by, status FROM ecm_core.cases WHERE id = ?", caseId);
            String assignedTo = (String) caseRow.get("assigned_to");
            String assignedGroup = (String) caseRow.get("assigned_to_group");
            String claimedBy = (String) caseRow.get("claimed_by");

            boolean isAssigned = (assignedTo != null && !assignedTo.isBlank())
                    || (assignedGroup != null && !assignedGroup.isBlank());

            // Skip ownership check for claim-like transitions (Start Working, Pick Up for Review)
            // These transitions auto-claim the case to the caller
            if (isAssigned && !isClaimTransition) {
                // Direct assignee can act
                boolean isDirectAssignee = (callerSub != null && callerSub.equals(assignedTo))
                        || (callerEmail != null && callerEmail.equals(assignedTo));
                // Claimer can act
                boolean isClaimer = (callerSub != null && callerSub.equals(claimedBy))
                        || (callerEmail != null && callerEmail.equals(claimedBy));
                // Admin bypass
                boolean isAdmin = isUserAdmin(callerEmail);

                if (!isDirectAssignee && !isClaimer && !isAdmin) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                            "This case is assigned to " +
                            (assignedGroup != null ? "group " + assignedGroup : assignedTo) +
                            ". You must claim it before taking action.");
                }
            }
        } catch (ResponseStatusException e) {
            throw e; // re-throw our own exceptions
        } catch (Exception e) {
            log.debug("Ownership check skipped for case {}: {}", caseId, e.getMessage());
        }

        // Determine if this is a "return" transition (moving back to IN_PROGRESS from review/approval)
        boolean isReturn = "IN_PROGRESS".equals(req.status()) && req.comment() != null && !req.comment().isBlank();
        boolean isTerminal = List.of("COMPLETED", "APPROVED", "REJECTED", "CANCELLED").contains(req.status());
        boolean isStartWorking = ("IN_PROGRESS".equals(req.status()) && !isReturn)
                || "UNDER_REVIEW".equals(req.status()); // Pick Up for Review also auto-claims

        int rows = jdbc.update("""
            UPDATE ecm_core.cases
            SET status = ?, updated_at = NOW(),
                returned_from_review = CASE WHEN ? THEN true ELSE
                    CASE WHEN ? IN ('REVIEW_PENDING', 'UNDER_REVIEW') THEN false ELSE returned_from_review END
                END,
                completed_at = CASE WHEN ? IN ('COMPLETED', 'REJECTED', 'CANCELLED') THEN NOW() ELSE completed_at END,
                assigned_to = CASE WHEN ? THEN NULL ELSE assigned_to END,
                assigned_to_name = CASE WHEN ? THEN NULL ELSE assigned_to_name END,
                assigned_to_group = CASE WHEN ? THEN NULL ELSE assigned_to_group END,
                claimed_by = CASE WHEN ? THEN NULL WHEN ? THEN ? ELSE claimed_by END,
                claimed_by_name = CASE WHEN ? THEN NULL WHEN ? THEN ? ELSE claimed_by_name END,
                claimed_at = CASE WHEN ? THEN NULL WHEN ? THEN NOW() ELSE claimed_at END
            WHERE id = ?
            """, req.status(), isReturn, req.status(), req.status(),
                isTerminal, isTerminal, isTerminal,
                isTerminal, isStartWorking, callerEmail,
                isTerminal, isStartWorking, callerEmail != null ? callerEmail.split("@")[0] : callerSub,
                isTerminal, isStartWorking,
                caseId);

        if (rows == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Case not found");
        log.info("Case status updated: id={}, status={}, returned={}, terminal={}, startWorking={}",
                caseId, req.status(), isReturn, isTerminal, isStartWorking);
        recordTimelineEvent(caseId, "CASE_STATUS_CHANGED",
                "Status changed to: " + req.status(),
                req.comment(), callerEmail != null ? callerEmail : callerSub);

        // Auto-assign to reviewer group when submitted for review
        if ("REVIEW_PENDING".equals(req.status())) {
            autoAssignToReviewerGroup(caseId, callerEmail);
        }

        // Update enrollment status based on case outcome
        if ("COMPLETED".equals(req.status()) || "APPROVED".equals(req.status())) {
            jdbc.update("""
                UPDATE ecm_core.party_product_enrollments
                SET status = 'ACTIVE', enrolled_at = NOW()
                WHERE case_id = ? AND status = 'PENDING'
                """, caseId);
            log.info("Enrollment activated for completed case {}", caseId);
        } else if ("REJECTED".equals(req.status())) {
            jdbc.update("""
                UPDATE ecm_core.party_product_enrollments
                SET status = 'REJECTED'
                WHERE case_id = ? AND status = 'PENDING'
                """, caseId);
            log.info("Enrollment rejected for case {}", caseId);
        } else if ("CANCELLED".equals(req.status())) {
            jdbc.update("""
                UPDATE ecm_core.party_product_enrollments
                SET status = 'CANCELLED'
                WHERE case_id = ? AND status = 'PENDING'
                """, caseId);
            log.info("Enrollment cancelled for case {}", caseId);
        }

        // Release document locks when case reaches terminal state
        if (isTerminal) {
            int unlocked = jdbc.update("""
                UPDATE ecm_core.documents
                SET locked_by = NULL, locked_at = NULL, lock_expires_at = NULL, updated_at = NOW()
                WHERE id IN (
                    SELECT cd.document_id::uuid FROM ecm_core.case_documents cd
                    WHERE cd.case_id = ? AND cd.document_id IS NOT NULL
                )
                AND locked_by IS NOT NULL
                """, caseId);
            if (unlocked > 0) {
                log.info("Auto-unlocked {} document(s) for completed case {}", unlocked, caseId);
            }
        }

        // Cancel active workflows when case is closed
        if (List.of("REJECTED", "CANCELLED").contains(req.status())) {
            cancelCaseWorkflows(caseId);
        }

        // Revoke all external participant access when case is closed
        if (List.of("COMPLETED", "REJECTED", "CANCELLED").contains(req.status())) {
            int revoked = jdbc.update("""
                UPDATE ecm_core.external_participants
                SET is_active = false, session_token = NULL, session_expires_at = NULL,
                    otp_code = NULL, updated_at = NOW()
                WHERE case_id = ? AND is_active = true
                """, caseId);
            if (revoked > 0) {
                log.info("Revoked {} external participant(s) for closed case {}", revoked, caseId);
                recordTimelineEvent(caseId, "CASE_STATUS_CHANGED",
                        revoked + " external participant access(es) revoked", null, "system");
            }
        }

        return getById(caseId);
    }

    // ── Checklist Operations ─────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ChecklistItem> getChecklist(UUID caseId) {
        return jdbc.query("""
            SELECT cd.id, cd.product_document_type_id, cd.document_id, cd.status,
                   cd.workflow_instance_id, cd.workflow_status,
                   cd.current_task_name, cd.current_task_assignee,
                   cd.override_status,
                   cd.is_verified, cd.verified_by, cd.verified_at,
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
                rs.getString("status"),
                rs.getString("workflow_instance_id"),
                rs.getString("workflow_status"),
                rs.getString("current_task_name"),
                rs.getString("current_task_assignee"),
                rs.getString("override_status"),
                rs.getBoolean("is_verified"),
                rs.getString("verified_by"),
                rs.getObject("verified_at", OffsetDateTime.class)
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
        recordTimelineEvent(caseId, "CHECKLIST_ITEM_UPLOADED",
                "Document linked to checklist item", null, uploadedBy);
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
        recordTimelineEvent(caseId, "CHECKLIST_ITEM_WAIVED",
                "Checklist item waived", req.reason(), waivedBy);
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
        recordTimelineEvent(caseId, "CASE_NOTE_ADDED",
                "Note added", req.note(), author);
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
        recordTimelineEvent(caseId, "CASE_STATUS_CHANGED",
                "Case cancelled", null, "system");

        // Cancel pending enrollment
        jdbc.update("""
            UPDATE ecm_core.party_product_enrollments SET status = 'CANCELLED'
            WHERE case_id = ? AND status = 'PENDING'
            """, caseId);

        // Revoke external access
        jdbc.update("""
            UPDATE ecm_core.external_participants
            SET is_active = false, session_token = NULL, otp_code = NULL, updated_at = NOW()
            WHERE case_id = ? AND is_active = true
            """, caseId);

        log.info("Case cancelled: id={}", caseId);
    }

    /** Delete a case — only NEW cases with no linked documents */
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

    // ── Timeline ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<CaseTimelineEvent> getTimeline(UUID caseId) {
        return jdbc.query("""
            SELECT event_type, description, detail, actor, timestamp
            FROM ecm_core.case_timeline_events
            WHERE case_id = ?
            ORDER BY timestamp DESC
            """, (rs, rowNum) -> new CaseTimelineEvent(
                rs.getString("event_type"),
                rs.getString("description"),
                rs.getString("detail"),
                rs.getString("actor"),
                rs.getObject("timestamp", OffsetDateTime.class)
        ), caseId);
    }

    // ── Assignment ─────────────────────────────────────────────────────────

    @Transactional
    public CaseResponse assignCase(UUID caseId, AssignCaseRequest req, String actor) {
        if (req.assignTo() != null && !req.assignTo().isBlank()) {
            // Assign to person
            jdbc.update("""
                UPDATE ecm_core.cases
                SET assigned_to = ?, assigned_to_name = ?, assigned_to_group = NULL,
                    claimed_by = NULL, claimed_by_name = NULL, claimed_at = NULL, updated_at = NOW()
                WHERE id = ?
                """, req.assignTo(), req.assignToName(), caseId);
            recordTimelineEvent(caseId, "CASE_STATUS_CHANGED",
                    "Case assigned to: " + (req.assignToName() != null ? req.assignToName() : req.assignTo()),
                    req.comment(), actor);
        } else if (req.assignToGroup() != null && !req.assignToGroup().isBlank()) {
            // Assign to group
            jdbc.update("""
                UPDATE ecm_core.cases
                SET assigned_to = NULL, assigned_to_name = NULL, assigned_to_group = ?,
                    claimed_by = NULL, claimed_by_name = NULL, claimed_at = NULL, updated_at = NOW()
                WHERE id = ?
                """, req.assignToGroup(), caseId);
            recordTimelineEvent(caseId, "CASE_STATUS_CHANGED",
                    "Case assigned to group: " + req.assignToGroup(), req.comment(), actor);
        }
        log.info("Case assigned: caseId={}, to={}, group={}, by={}",
                caseId, req.assignTo(), req.assignToGroup(), actor);

        // Publish notification event
        try {
            String assignTarget = req.assignToGroup() != null ? req.assignToGroup()
                    : (req.assignToName() != null ? req.assignToName() : req.assignTo());
            publishCaseEvent("case.assigned", Map.of(
                    "caseId", caseId.toString(),
                    "assignedTo", req.assignTo() != null ? req.assignTo() : "",
                    "assignedToGroup", req.assignToGroup() != null ? req.assignToGroup() : "",
                    "assignedBy", actor
            ));
        } catch (Exception e) {
            log.warn("Failed to publish case.assigned event: {}", e.getMessage());
        }

        return getById(caseId);
    }

    @Transactional
    public CaseResponse claimCase(UUID caseId, String claimedBy, String claimedByName) {
        jdbc.update("""
            UPDATE ecm_core.cases
            SET claimed_by = ?, claimed_by_name = ?, claimed_at = NOW(), updated_at = NOW()
            WHERE id = ?
            """, claimedBy, claimedByName, caseId);
        recordTimelineEvent(caseId, "CASE_STATUS_CHANGED",
                "Case claimed by: " + (claimedByName != null ? claimedByName : claimedBy),
                null, claimedBy);
        log.info("Case claimed: caseId={}, by={}", caseId, claimedBy);
        return getById(caseId);
    }

    // ── Verification ─────────────────────────────────────────────────────────

    @Transactional
    public CaseResponse verifyItems(UUID caseId, VerifyItemsRequest req, String verifiedBy) {
        // Reset all items to unverified first
        jdbc.update("""
            UPDATE ecm_core.case_documents
            SET is_verified = false, verified_by = NULL, verified_at = NULL, updated_at = NOW()
            WHERE case_id = ?
            """, caseId);

        // Mark specified items as verified
        if (req.verifiedItemIds() != null && !req.verifiedItemIds().isEmpty()) {
            for (Integer itemId : req.verifiedItemIds()) {
                jdbc.update("""
                    UPDATE ecm_core.case_documents
                    SET is_verified = true, verified_by = ?, verified_at = NOW(), updated_at = NOW()
                    WHERE id = ? AND case_id = ?
                    """, verifiedBy, itemId, caseId);
            }
            recordTimelineEvent(caseId, "CHECKLIST_ITEM_APPROVED",
                    req.verifiedItemIds().size() + " item(s) verified", null, verifiedBy);
        }

        // No auto-transition — case worker manually clicks "Submit for Review" when ready.
        // Verification is a checkpoint, not a trigger.

        log.info("Verification saved: caseId={}, verified={}, by={}",
                caseId, req.verifiedItemIds() != null ? req.verifiedItemIds().size() : 0,
                verifiedBy);
        return getById(caseId);
    }

    // ── Request Additional Docs ──────────────────────────────────────────────

    @Transactional
    public CaseResponse requestAdditionalDocs(UUID caseId, RequestAdditionalDocsRequest req, String actor) {
        if (req.categoryIds() == null || req.categoryIds().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At least one document category is required");
        }

        // Add new checklist items from the selected categories
        int added = 0;
        for (Integer catId : req.categoryIds()) {
            // Get category name for the item name
            String catName = jdbc.queryForObject(
                    "SELECT name FROM ecm_admin.document_categories WHERE id = ?", String.class, catId);
            String catCode = jdbc.queryForObject(
                    "SELECT code FROM ecm_admin.document_categories WHERE id = ?", String.class, catId);

            // Find the product ID for this case to create a matching product_document_type
            Integer productId = jdbc.queryForObject(
                    "SELECT product_id FROM ecm_core.cases WHERE id = ?", Integer.class, caseId);

            // Check if a product_document_type already exists for this product+category
            Integer pdtId;
            try {
                pdtId = jdbc.queryForObject("""
                    SELECT id FROM ecm_admin.product_document_types
                    WHERE product_id = ? AND category_id = ? AND is_active = true
                    ORDER BY id LIMIT 1
                    """, Integer.class, productId, catId);
            } catch (Exception e) {
                // Create a new product_document_type for this additional request
                pdtId = jdbc.queryForObject("""
                    INSERT INTO ecm_admin.product_document_types
                        (product_id, category_id, name, code, source_type, on_upload_action, is_required, sort_order)
                    VALUES (?, ?, ?, ?, 'UPLOAD', 'OCR_ONLY', TRUE, 99)
                    RETURNING id
                    """, Integer.class, productId, catId,
                        "Additional: " + catName,
                        "ADDL_" + catCode + "_" + System.currentTimeMillis() % 10000);
            }

            // Add to case checklist
            jdbc.update("""
                INSERT INTO ecm_core.case_documents (case_id, product_document_type_id, status)
                VALUES (?, ?, 'PENDING')
                """, caseId, pdtId);
            added++;
        }

        // Move case back to IN_PROGRESS with returned flag
        jdbc.update("""
            UPDATE ecm_core.cases SET status = 'IN_PROGRESS', returned_from_review = true, updated_at = NOW() WHERE id = ?
            """, caseId);

        // Reassign if specified
        if (req.reassignTo() != null && !req.reassignTo().isBlank()) {
            jdbc.update("""
                UPDATE ecm_core.cases
                SET assigned_to = ?, assigned_to_name = ?, assigned_to_group = NULL,
                    claimed_by = NULL, claimed_by_name = NULL, claimed_at = NULL
                WHERE id = ?
                """, req.reassignTo(), req.reassignToName(), caseId);
        }

        recordTimelineEvent(caseId, "CASE_STATUS_CHANGED",
                added + " additional document(s) requested. Case returned to IN_PROGRESS.",
                req.comment(), actor);

        log.info("Additional docs requested: caseId={}, categories={}, by={}", caseId, req.categoryIds(), actor);
        return getById(caseId);
    }

    /**
     * Mark a checklist item as complete (self-certified by case worker).
     * Status: UPLOADED → APPROVED
     */
    @Transactional
    public CaseResponse completeChecklistItem(UUID caseId, Integer itemId, String completedBy) {
        jdbc.update("""
            UPDATE ecm_core.case_documents
            SET status = 'APPROVED', reviewed_by = ?, reviewed_at = NOW(), updated_at = NOW()
            WHERE id = ? AND case_id = ? AND status IN ('UPLOADED', 'PENDING')
            """, completedBy, itemId, caseId);

        recordTimelineEvent(caseId, "CHECKLIST_ITEM_APPROVED",
                "Checklist item marked complete (self-certified)", null, completedBy);

        log.info("Checklist item completed: caseId={}, itemId={}, by={}", caseId, itemId, completedBy);
        return getById(caseId);
    }

    /**
     * Reopen a completed checklist item for re-review.
     * Status: APPROVED → UPLOADED (document stays linked)
     */
    @Transactional
    public CaseResponse reopenChecklistItem(UUID caseId, Integer itemId, String reopenedBy) {
        jdbc.update("""
            UPDATE ecm_core.case_documents
            SET status = 'UPLOADED', reviewed_by = NULL, reviewed_at = NULL, updated_at = NOW()
            WHERE id = ? AND case_id = ? AND status = 'APPROVED'
            """, itemId, caseId);

        recordTimelineEvent(caseId, "CHECKLIST_ITEM_REOPENED",
                "Checklist item reopened for re-review", null, reopenedBy);

        log.info("Checklist item reopened: caseId={}, itemId={}, by={}", caseId, itemId, reopenedBy);
        return getById(caseId);
    }

    /**
     * Add a new checklist item to a case.
     * Used by case workers who need additional documents beyond the product template.
     */
    @Transactional
    public CaseResponse addChecklistItem(UUID caseId,
                                          com.ecm.admin.controller.CaseController.AddChecklistItemRequest req,
                                          String actor) {
        // Get product ID for this case
        Integer productId = jdbc.queryForObject(
                "SELECT product_id FROM ecm_core.cases WHERE id = ?", Integer.class, caseId);

        Integer pdtId;
        String itemName;

        if (req.categoryId() != null) {
            // Category-based: find or create product_document_type
            String catName = jdbc.queryForObject(
                    "SELECT name FROM ecm_admin.document_categories WHERE id = ?", String.class, req.categoryId());
            String catCode = jdbc.queryForObject(
                    "SELECT code FROM ecm_admin.document_categories WHERE id = ?", String.class, req.categoryId());
            itemName = catName;

            try {
                pdtId = jdbc.queryForObject("""
                    SELECT id FROM ecm_admin.product_document_types
                    WHERE product_id = ? AND category_id = ? AND is_active = true
                    ORDER BY id LIMIT 1
                    """, Integer.class, productId, req.categoryId());
            } catch (Exception e) {
                pdtId = jdbc.queryForObject("""
                    INSERT INTO ecm_admin.product_document_types
                        (product_id, category_id, name, code, source_type, on_upload_action, is_required, sort_order)
                    VALUES (?, ?, ?, ?, 'UPLOAD', 'OCR_ONLY', ?, 99)
                    RETURNING id
                    """, Integer.class, productId, req.categoryId(),
                        catName, "ADDL_" + catCode + "_" + System.currentTimeMillis() % 10000,
                        req.isRequired());
            }
        } else if (req.customName() != null && !req.customName().isBlank()) {
            // Custom name: create an ad-hoc product_document_type with no category
            itemName = req.customName();
            pdtId = jdbc.queryForObject("""
                INSERT INTO ecm_admin.product_document_types
                    (product_id, category_id, name, code, source_type, on_upload_action, is_required, sort_order)
                VALUES (?, NULL, ?, ?, 'UPLOAD', 'NONE', ?, 99)
                RETURNING id
                """, Integer.class, productId,
                    req.customName(),
                    "CUSTOM_" + System.currentTimeMillis() % 100000,
                    req.isRequired());
        } else {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Either categoryId or customName is required");
        }

        // Add to case checklist
        jdbc.update("""
            INSERT INTO ecm_core.case_documents (case_id, product_document_type_id, status)
            VALUES (?, ?, 'PENDING')
            """, caseId, pdtId);

        recordTimelineEvent(caseId, "CHECKLIST_ITEM_ADDED",
                "Document request added: " + itemName, null, actor);

        log.info("Checklist item added: caseId={}, item={}, by={}", caseId, itemName, actor);
        return getById(caseId);
    }

    // ── Workflow → Case Bridge ──────────────────────────────────────────────

    /**
     * Called when a document workflow completes (via RabbitMQ workflow.completed event).
     * Updates the checklist item status based on the workflow decision.
     * If all required items are satisfied, auto-transitions the case.
     */
    @Transactional
    public void onWorkflowCompleted(String processInstanceId, String documentId, String decision) {
        // Find checklist item by workflow_instance_id or document_id
        String findSql = processInstanceId != null
                ? "SELECT cd.id, cd.case_id FROM ecm_core.case_documents cd WHERE cd.workflow_instance_id = ?"
                : "SELECT cd.id, cd.case_id FROM ecm_core.case_documents cd WHERE cd.document_id = ?::uuid";
        String findParam = processInstanceId != null ? processInstanceId : documentId;

        List<Map<String, Object>> items;
        try {
            items = jdbc.queryForList(findSql, findParam);
        } catch (Exception e) {
            log.debug("No checklist item found for processInstanceId={}, documentId={}", processInstanceId, documentId);
            return; // Not a case-linked document — normal standalone workflow
        }

        if (items.isEmpty()) {
            log.debug("No checklist item linked to processInstanceId={} or documentId={}", processInstanceId, documentId);
            return;
        }

        for (Map<String, Object> item : items) {
            Integer itemId = (Integer) item.get("id");
            UUID caseId = (UUID) item.get("case_id");

            String newStatus;
            if ("APPROVED".equalsIgnoreCase(decision)) {
                newStatus = "APPROVED";
            } else if ("REJECTED".equalsIgnoreCase(decision)) {
                newStatus = "REJECTED";
            } else {
                newStatus = "REVIEWED";
            }

            jdbc.update("""
                UPDATE ecm_core.case_documents
                SET workflow_status = 'COMPLETED', status = ?, updated_at = NOW()
                WHERE id = ?
                """, newStatus, itemId);

            log.info("Checklist item {} updated: workflow completed, status={}, caseId={}", itemId, newStatus, caseId);

            recordTimelineEvent(caseId, "CHECKLIST_ITEM_" + newStatus,
                    "Document workflow completed: " + decision, null, "workflow");

            // Check if all required items are now satisfied → auto-transition case
            checkAndAutoTransitionCase(caseId);
        }
    }

    /**
     * After a checklist item update, check if all required items are satisfied.
     * If so, auto-transition the case to REVIEW_PENDING.
     */
    private void checkAndAutoTransitionCase(UUID caseId) {
        try {
            String currentStatus = jdbc.queryForObject(
                    "SELECT status FROM ecm_core.cases WHERE id = ?", String.class, caseId);

            if (!"IN_PROGRESS".equals(currentStatus)) return;

            List<Map<String, Object>> requiredItems = jdbc.queryForList("""
                SELECT cd.status, cd.is_verified
                FROM ecm_core.case_documents cd
                JOIN ecm_admin.product_document_types pdt ON pdt.id = cd.product_document_type_id
                WHERE cd.case_id = ? AND pdt.is_required = true
                """, caseId);

            if (requiredItems.isEmpty()) return;

            boolean allSatisfied = requiredItems.stream().allMatch(r -> {
                String status = (String) r.get("status");
                return "APPROVED".equals(status) || "WAIVED".equals(status) || "UPLOADED".equals(status);
            });

            if (allSatisfied) {
                jdbc.update("""
                    UPDATE ecm_core.cases SET status = 'REVIEW_PENDING', returned_from_review = false, updated_at = NOW()
                    WHERE id = ? AND status = 'IN_PROGRESS'
                    """, caseId);
                recordTimelineEvent(caseId, "CASE_STATUS_CHANGED",
                        "All required documents satisfied — case moved to Review Queue", null, "system");
                autoAssignToReviewerGroup(caseId, "system");
                log.info("Case {} auto-transitioned to REVIEW_PENDING (all required items satisfied)", caseId);
            }
        } catch (Exception e) {
            log.error("Failed to check auto-transition for case {}: {}", caseId, e.getMessage());
        }
    }

    /**
     * Auto-assign case to ECM_REVIEWER group when moving to REVIEW_PENDING.
     * Only assigns if not already assigned to a specific person or group.
     */
    private void autoAssignToReviewerGroup(UUID caseId, String actor) {
        try {
            int updated = jdbc.update("""
                UPDATE ecm_core.cases
                SET assigned_to_group = 'ECM_REVIEWER',
                    assigned_to = NULL, assigned_to_name = NULL,
                    claimed_by = NULL, claimed_by_name = NULL, claimed_at = NULL,
                    updated_at = NOW()
                WHERE id = ? AND (assigned_to_group IS NULL OR assigned_to_group = '')
                """, caseId);

            if (updated > 0) {
                recordTimelineEvent(caseId, "CASE_ASSIGNED",
                        "Auto-assigned to ECM_REVIEWER group for review", null,
                        actor != null ? actor : "system");

                // Publish assignment event for notifications
                try {
                    var caseRow = jdbc.queryForMap(
                            "SELECT external_ref FROM ecm_core.cases WHERE id = ?", caseId);
                    String caseRef = (String) caseRow.get("external_ref");

                    Map<String, Object> event = new java.util.HashMap<>();
                    event.put("caseId", caseId.toString());
                    event.put("caseRef", caseRef != null ? caseRef : "");
                    event.put("assignedToGroup", "ECM_REVIEWER");
                    event.put("assignedBy", actor != null ? actor : "system");
                    rabbitTemplate.convertAndSend("ecm.admin", "case.assigned", event);
                } catch (Exception e) {
                    log.debug("Failed to publish case.assigned event for auto-assign: {}", e.getMessage());
                }

                log.info("Case {} auto-assigned to ECM_REVIEWER group", caseId);
            }
        } catch (Exception e) {
            log.warn("Failed to auto-assign case {} to reviewer group: {}", caseId, e.getMessage());
        }
    }

    /**
     * Cancel all active workflows for a case's documents.
     * Called when a case is cancelled or rejected.
     */
    @Transactional
    public void cancelCaseWorkflows(UUID caseId) {
        List<Map<String, Object>> activeWorkflows = jdbc.queryForList("""
            SELECT cd.id, cd.workflow_instance_id
            FROM ecm_core.case_documents cd
            WHERE cd.case_id = ? AND cd.workflow_status = 'ACTIVE' AND cd.workflow_instance_id IS NOT NULL
            """, caseId);

        if (activeWorkflows.isEmpty()) return;

        int cancelled = 0;
        for (Map<String, Object> wf : activeWorkflows) {
            Integer itemId = (Integer) wf.get("id");
            String workflowInstanceId = (String) wf.get("workflow_instance_id");

            jdbc.update("""
                UPDATE ecm_core.case_documents
                SET workflow_status = 'TERMINATED', status = 'CANCELLED', updated_at = NOW()
                WHERE id = ?
                """, itemId);

            // Publish cancel event to ecm-workflow so it can stop the Flowable process
            try {
                rabbitTemplate.convertAndSend("ecm.admin", "case.workflow.cancel", Map.of(
                        "caseId", caseId.toString(),
                        "workflowInstanceId", workflowInstanceId
                ));
            } catch (Exception e) {
                log.warn("Failed to publish workflow cancel event for instance {}: {}",
                        workflowInstanceId, e.getMessage());
            }
            cancelled++;
        }

        if (cancelled > 0) {
            recordTimelineEvent(caseId, "WORKFLOWS_CANCELLED",
                    cancelled + " active workflow(s) terminated due to case closure", null, "system");
            log.info("Cancelled {} active workflow(s) for case {}", cancelled, caseId);
        }
    }

    /** Record a timeline event */
    @Transactional
    private boolean isUserAdmin(String email) {
        if (email == null) return false;
        try {
            Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM ecm_core.user_roles ur
                JOIN ecm_core.users u ON u.id = ur.user_id
                JOIN ecm_core.roles r ON r.id = ur.role_id
                WHERE u.email = ? AND r.name IN ('ECM_ADMIN', 'ECM_SUPER_ADMIN')
                """, Integer.class, email);
            return count != null && count > 0;
        } catch (Exception e) {
            return false;
        }
    }

    public void recordTimelineEvent(UUID caseId, String eventType, String description,
                                     String detail, String actor) {
        jdbc.update("""
            INSERT INTO ecm_core.case_timeline_events (case_id, event_type, description, detail, actor)
            VALUES (?, ?, ?, ?, ?)
            """, caseId, eventType, description, detail, actor);
    }

    // ── Override System ───────────────────────────────────────────────────────

    /** Non-admin user requests an override for a checklist item */
    @Transactional
    public OverrideRequestResponse requestOverride(UUID caseId, Integer itemId,
                                                    OverrideRequest req, String requestedBy) {
        // Get item name for denormalization
        String itemName = jdbc.queryForObject("""
            SELECT pdt.name FROM ecm_core.case_documents cd
            JOIN ecm_admin.product_document_types pdt ON pdt.id = cd.product_document_type_id
            WHERE cd.id = ? AND cd.case_id = ?
            """, String.class, itemId, caseId);

        jdbc.update("""
            INSERT INTO ecm_core.case_override_requests
                (case_id, checklist_item_id, item_name, reason, status, requested_by)
            VALUES (?, ?, ?, ?, 'PENDING', ?)
            """, caseId, itemId, itemName, req.reason(), requestedBy);

        // Update checklist item override_status
        jdbc.update("""
            UPDATE ecm_core.case_documents SET override_status = 'PENDING', updated_at = NOW()
            WHERE id = ? AND case_id = ?
            """, itemId, caseId);

        recordTimelineEvent(caseId, "OVERRIDE_REQUESTED",
                "Override requested for: " + itemName, req.reason(), requestedBy);

        log.info("Override requested: caseId={}, itemId={}, by={}", caseId, itemId, requestedBy);

        // Return the created request
        return getLatestOverrideRequest(caseId, itemId);
    }

    /** Admin reviews an override request */
    @Transactional
    public OverrideRequestResponse reviewOverrideRequest(Integer requestId,
                                                          ReviewOverrideRequest req, String reviewedBy) {
        // Fetch the request
        var requests = jdbc.query("""
            SELECT id, case_id, checklist_item_id, item_name, reason, status,
                   requested_by, requested_at
            FROM ecm_core.case_override_requests WHERE id = ?
            """, (rs, rowNum) -> new OverrideRequestResponse(
                rs.getInt("id"),
                UUID.fromString(rs.getString("case_id")),
                rs.getInt("checklist_item_id"),
                rs.getString("item_name"),
                rs.getString("reason"),
                rs.getString("status"),
                rs.getString("requested_by"),
                rs.getObject("requested_at", OffsetDateTime.class),
                null, null, null
        ), requestId);

        if (requests.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Override request not found");
        }

        var overrideReq = requests.get(0);
        if (!"PENDING".equals(overrideReq.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Request already reviewed");
        }

        String decision = req.decision().toUpperCase();
        if (!decision.equals("APPROVED") && !decision.equals("DENIED")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Decision must be APPROVED or DENIED");
        }

        // Update the override request
        jdbc.update("""
            UPDATE ecm_core.case_override_requests
            SET status = ?, reviewed_by = ?, review_reason = ?, reviewed_at = NOW()
            WHERE id = ?
            """, decision, reviewedBy, req.reason(), requestId);

        // Update checklist item
        jdbc.update("""
            UPDATE ecm_core.case_documents SET override_status = ?, updated_at = NOW()
            WHERE id = ? AND case_id = ?
            """, decision, overrideReq.checklistItemId(), overrideReq.caseId());

        // If approved, mark item as APPROVED (bypass workflow)
        if ("APPROVED".equals(decision)) {
            jdbc.update("""
                UPDATE ecm_core.case_documents
                SET status = 'APPROVED', bypassed_by = ?, bypassed_reason = ?, bypassed_at = NOW(), updated_at = NOW()
                WHERE id = ? AND case_id = ?
                """, reviewedBy, "Override approved: " + req.reason(),
                    overrideReq.checklistItemId(), overrideReq.caseId());
        }

        String eventType = "APPROVED".equals(decision) ? "OVERRIDE_APPROVED" : "OVERRIDE_DENIED";
        recordTimelineEvent(overrideReq.caseId(), eventType,
                "Override " + decision.toLowerCase() + " for: " + overrideReq.itemName(),
                req.reason(), reviewedBy);

        log.info("Override reviewed: requestId={}, decision={}, by={}", requestId, decision, reviewedBy);

        return jdbc.query("""
            SELECT id, case_id, checklist_item_id, item_name, reason, status,
                   requested_by, requested_at, reviewed_by, review_reason, reviewed_at
            FROM ecm_core.case_override_requests WHERE id = ?
            """, (rs, rowNum) -> new OverrideRequestResponse(
                rs.getInt("id"),
                UUID.fromString(rs.getString("case_id")),
                rs.getInt("checklist_item_id"),
                rs.getString("item_name"),
                rs.getString("reason"),
                rs.getString("status"),
                rs.getString("requested_by"),
                rs.getObject("requested_at", OffsetDateTime.class),
                rs.getString("reviewed_by"),
                rs.getString("review_reason"),
                rs.getObject("reviewed_at", OffsetDateTime.class)
        ), requestId).get(0);
    }

    /** List override requests (optionally filtered by caseId) */
    @Transactional(readOnly = true)
    public List<OverrideRequestResponse> listOverrideRequests(UUID caseId) {
        String sql = """
            SELECT id, case_id, checklist_item_id, item_name, reason, status,
                   requested_by, requested_at, reviewed_by, review_reason, reviewed_at
            FROM ecm_core.case_override_requests
            """;
        List<Object> params = new ArrayList<>();
        if (caseId != null) {
            sql += " WHERE case_id = ?";
            params.add(caseId);
        }
        sql += " ORDER BY requested_at DESC";

        return jdbc.query(sql, (rs, rowNum) -> new OverrideRequestResponse(
                rs.getInt("id"),
                UUID.fromString(rs.getString("case_id")),
                rs.getInt("checklist_item_id"),
                rs.getString("item_name"),
                rs.getString("reason"),
                rs.getString("status"),
                rs.getString("requested_by"),
                rs.getObject("requested_at", OffsetDateTime.class),
                rs.getString("reviewed_by"),
                rs.getString("review_reason"),
                rs.getObject("reviewed_at", OffsetDateTime.class)
        ), params.toArray());
    }

    /** Admin directly bypasses a checklist item (no request needed) */
    @Transactional
    public CaseResponse adminBypassItem(UUID caseId, Integer itemId,
                                         AdminBypassRequest req, String bypassedBy) {
        // Get item name for timeline
        String itemName = jdbc.queryForObject("""
            SELECT pdt.name FROM ecm_core.case_documents cd
            JOIN ecm_admin.product_document_types pdt ON pdt.id = cd.product_document_type_id
            WHERE cd.id = ? AND cd.case_id = ?
            """, String.class, itemId, caseId);

        jdbc.update("""
            UPDATE ecm_core.case_documents
            SET status = 'APPROVED', override_status = 'APPROVED',
                bypassed_by = ?, bypassed_reason = ?, bypassed_at = NOW(), updated_at = NOW()
            WHERE id = ? AND case_id = ?
            """, bypassedBy, req.reason(), itemId, caseId);

        recordTimelineEvent(caseId, "ADMIN_BYPASS",
                "Admin bypassed: " + itemName, req.reason(), bypassedBy);

        log.info("Admin bypass: caseId={}, itemId={}, by={}", caseId, itemId, bypassedBy);
        return getById(caseId);
    }

    /** Start a workflow for a checklist item — calls ecm-workflow to create a real Flowable process */
    @Transactional
    public CaseResponse startChecklistWorkflow(UUID caseId, Integer itemId, String startedBy) {
        // Fetch checklist item details: document ID, name, category
        var items = jdbc.query("""
            SELECT cd.document_id, cd.product_document_type_id,
                   pdt.name AS doc_type_name, pdt.category_id,
                   d.name AS doc_name
            FROM ecm_core.case_documents cd
            JOIN ecm_admin.product_document_types pdt ON pdt.id = cd.product_document_type_id
            LEFT JOIN ecm_core.documents d ON d.id = cd.document_id
            WHERE cd.id = ? AND cd.case_id = ?
            """, (rs, rowNum) -> {
                Map<String, Object> row = new HashMap<>();
                row.put("documentId", rs.getString("document_id") != null ? rs.getString("document_id") : "");
                row.put("docTypeName", rs.getString("doc_type_name"));
                row.put("docName", rs.getString("doc_name") != null ? rs.getString("doc_name") : rs.getString("doc_type_name"));
                row.put("categoryId", rs.getObject("category_id") != null ? rs.getInt("category_id") : 0);
                return row;
            }, itemId, caseId);

        if (items.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Checklist item not found");
        }

        var item = items.get(0);
        String documentId = (String) item.get("documentId");
        String docName = (String) item.get("docName");
        String docTypeName = (String) item.get("docTypeName");
        Integer categoryId = (Integer) item.get("categoryId");

        if (documentId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Cannot start workflow — no document linked to this checklist item");
        }

        // Resolve workflow definition by category — use the first active one
        Integer workflowDefId = null;
        try {
            workflowDefId = jdbc.queryForObject("""
                SELECT id FROM ecm_workflow.workflow_definition_configs
                WHERE is_active = true
                ORDER BY id ASC LIMIT 1
                """, Integer.class);
        } catch (Exception e) {
            log.warn("No workflow definition config found: {}", e.getMessage());
        }

        if (workflowDefId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No active workflow definition configured. Publish a workflow template first.");
        }

        // Call ecm-workflow to start the real Flowable process
        var result = workflowClient.startWorkflow(documentId, docName, workflowDefId, categoryId, startedBy);

        if (result == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to start workflow — ecm-workflow service may be down");
        }

        // Update checklist item with the real process instance ID
        jdbc.update("""
            UPDATE ecm_core.case_documents
            SET workflow_instance_id = ?, workflow_status = 'ACTIVE',
                status = 'UNDER_REVIEW', updated_at = NOW()
            WHERE id = ? AND case_id = ?
            """, result.processInstanceId(), itemId, caseId);

        recordTimelineEvent(caseId, "WORKFLOW_STARTED",
                "Workflow started for: " + docTypeName, result.processInstanceId(), startedBy);

        log.info("Workflow started: caseId={}, itemId={}, processInstanceId={}",
                caseId, itemId, result.processInstanceId());
        return getById(caseId);
    }

    /**
     * Send a checklist item's document for DocuSign signature.
     * Calls ecm-eforms DocuSign API to create an envelope.
     */
    @Transactional
    public CaseResponse sendChecklistItemForSignature(UUID caseId, Integer itemId,
                                                       com.ecm.admin.controller.CaseController.SendForSignatureRequest req,
                                                       String sentBy) {
        // Get the document linked to this checklist item
        var items = jdbc.query("""
            SELECT cd.document_id, cd.status, d.name as doc_name
            FROM ecm_core.case_documents cd
            LEFT JOIN ecm_core.documents d ON d.id = cd.document_id::uuid
            WHERE cd.id = ? AND cd.case_id = ?
            """, (rs, rn) -> {
                var row = new java.util.HashMap<String, Object>();
                row.put("documentId", rs.getString("document_id"));
                row.put("docName", rs.getString("doc_name"));
                row.put("status", rs.getString("status"));
                return row;
            }, itemId, caseId);

        if (items.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Checklist item not found");
        }

        String documentId = (String) items.get(0).get("documentId");
        String docName = (String) items.get(0).get("docName");

        if (documentId == null || documentId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No document linked to this checklist item — upload a document first");
        }

        // Call ecm-eforms to create DocuSign envelope
        try {
            var body = new java.util.HashMap<String, Object>();
            body.put("documentId", documentId);
            body.put("recipientEmail", req.signerEmail());
            body.put("recipientName", req.signerName());
            body.put("subject", req.emailSubject() != null && !req.emailSubject().isBlank()
                    ? req.emailSubject()
                    : "ECM — Please sign: " + (docName != null ? docName : "Document"));
            body.put("placement", req.placement() != null ? req.placement() : "lastPage");
            if (req.signaturePage() != null) body.put("signaturePage", req.signaturePage());
            if (req.signatureX() != null) body.put("signatureX", req.signatureX());
            if (req.signatureY() != null) body.put("signatureY", req.signatureY());
            body.put("requireInitials", req.requireInitials());
            body.put("requireDateSigned", req.requireDateSigned());

            org.springframework.web.client.RestTemplate rest = new org.springframework.web.client.RestTemplate();
            String eformsUrl = "http://localhost:8084/api/eforms/docusign/create-envelope";

            @SuppressWarnings("unchecked")
            var response = rest.postForEntity(eformsUrl,
                    new org.springframework.http.HttpEntity<>(body,
                            new org.springframework.http.HttpHeaders() {{ setContentType(org.springframework.http.MediaType.APPLICATION_JSON); }}),
                    java.util.Map.class);

            String envelopeId = response.getBody() != null
                    ? String.valueOf(response.getBody().get("envelopeId")) : "UNKNOWN";

            // Update checklist item with DocuSign info
            jdbc.update("""
                UPDATE ecm_core.case_documents
                SET status = 'PENDING_SIGNATURE', updated_at = NOW()
                WHERE id = ? AND case_id = ?
                """, itemId, caseId);

            recordTimelineEvent(caseId, "DOCUSIGN_SENT",
                    "Document sent for signature to " + req.signerEmail() +
                    " (envelope: " + envelopeId + ")", null, sentBy);

            log.info("DocuSign sent: caseId={}, itemId={}, envelopeId={}, signer={}",
                    caseId, itemId, envelopeId, req.signerEmail());

        } catch (Exception e) {
            log.error("Failed to send for signature: caseId={}, itemId={}: {}",
                    caseId, itemId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to create DocuSign envelope: " + e.getMessage());
        }

        return getById(caseId);
    }

    private OverrideRequestResponse getLatestOverrideRequest(UUID caseId, Integer itemId) {
        return jdbc.query("""
            SELECT id, case_id, checklist_item_id, item_name, reason, status,
                   requested_by, requested_at, reviewed_by, review_reason, reviewed_at
            FROM ecm_core.case_override_requests
            WHERE case_id = ? AND checklist_item_id = ?
            ORDER BY requested_at DESC LIMIT 1
            """, (rs, rowNum) -> new OverrideRequestResponse(
                rs.getInt("id"),
                UUID.fromString(rs.getString("case_id")),
                rs.getInt("checklist_item_id"),
                rs.getString("item_name"),
                rs.getString("reason"),
                rs.getString("status"),
                rs.getString("requested_by"),
                rs.getObject("requested_at", OffsetDateTime.class),
                rs.getString("reviewed_by"),
                rs.getString("review_reason"),
                rs.getObject("reviewed_at", OffsetDateTime.class)
        ), caseId, itemId).get(0);
    }

    // ── External Participants ──────────────────────────────────────────────

    @Transactional
    public ParticipantResponse addParticipant(UUID caseId, AddParticipantRequest req, String invitedBy) {
        jdbc.update("""
            INSERT INTO ecm_core.external_participants
                (case_id, name, email, organization, role, phone, invited_by)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (case_id, email) DO UPDATE SET
                name = EXCLUDED.name, organization = EXCLUDED.organization,
                role = EXCLUDED.role, phone = EXCLUDED.phone, is_active = true,
                updated_at = NOW()
            """, caseId, req.name(), req.email(), req.organization(), req.role(), req.phone(), invitedBy);

        recordTimelineEvent(caseId, "CASE_STATUS_CHANGED",
                "External participant added: " + req.name() + " (" + req.role() + ")",
                req.email(), invitedBy);

        log.info("Participant added: caseId={}, email={}, role={}", caseId, req.email(), req.role());

        // Get the access token for the invitation link
        ParticipantResponse participant = getParticipantByEmail(caseId, req.email());

        // Publish invite event → ecm-notification sends email with link
        publishCaseEvent("case.participant.added", Map.of(
                "email", req.email(),
                "name", req.name(),
                "role", req.role(),
                "inviteToken", participant.inviteToken().toString()
        ));

        return participant;
    }

    @Transactional(readOnly = true)
    public List<ParticipantResponse> listParticipants(UUID caseId) {
        return jdbc.query("""
            SELECT id, case_id, name, email, organization, role, phone,
                   invite_token, token_expires_at, last_accessed_at, invited_by, is_active, created_at
            FROM ecm_core.external_participants
            WHERE case_id = ? ORDER BY created_at
            """, (rs, rowNum) -> mapParticipant(rs), caseId);
    }

    @Transactional
    public void removeParticipant(UUID caseId, Integer participantId, String actor) {
        jdbc.update("""
            UPDATE ecm_core.external_participants SET is_active = false, updated_at = NOW()
            WHERE id = ? AND case_id = ?
            """, participantId, caseId);

        recordTimelineEvent(caseId, "CASE_STATUS_CHANGED",
                "External participant removed", null, actor);
    }

    @Transactional
    public void shareDocuments(UUID caseId, ShareDocumentsRequest req, String sharedBy) {
        for (Integer docId : req.caseDocumentIds()) {
            jdbc.update("""
                INSERT INTO ecm_core.case_document_shares (case_id, case_document_id, participant_id, shared_by)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (case_document_id, participant_id) DO NOTHING
                """, caseId, docId, req.participantId(), sharedBy);
        }
        recordTimelineEvent(caseId, "CASE_STATUS_CHANGED",
                req.caseDocumentIds().size() + " document(s) shared with participant #" + req.participantId(),
                null, sharedBy);
        log.info("Documents shared: caseId={}, participantId={}, docs={}", caseId, req.participantId(), req.caseDocumentIds().size());
    }

    // ── Secure External Access (OTP + Session Token + Rate Limiting) ────────

    private static final int MAX_OTP_ATTEMPTS = 5;
    private static final int OTP_EXPIRY_MINUTES = 5;
    private static final int LOCKOUT_MINUTES = 15;

    @Transactional
    public String generateOtp(UUID inviteToken, String ipAddress) {
        var p = loadParticipantByToken(inviteToken);

        // Rate limit check
        Integer failedAttempts = (Integer) p.get("failedOtpAttempts");
        OffsetDateTime lockedUntil = (OffsetDateTime) p.get("lockedUntil");
        if (lockedUntil != null && lockedUntil.isAfter(OffsetDateTime.now())) {
            logExternalAccess((Integer) p.get("id"), (String) p.get("caseId"), "OTP_LOCKED", ipAddress, null,
                    "Account locked until " + lockedUntil);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many attempts. Try again after " + LOCKOUT_MINUTES + " minutes.");
        }
        if (failedAttempts != null && failedAttempts >= MAX_OTP_ATTEMPTS) {
            // Lock the token
            jdbc.update(
                "UPDATE ecm_core.external_participants SET locked_until = NOW() + INTERVAL '" +
                LOCKOUT_MINUTES + " minutes', failed_otp_attempts = 0 WHERE id = ?",
                p.get("id"));
            logExternalAccess((Integer) p.get("id"), (String) p.get("caseId"), "OTP_LOCKED", ipAddress, null,
                    "Locked after " + MAX_OTP_ATTEMPTS + " failed attempts");
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many failed attempts. Account locked for " + LOCKOUT_MINUTES + " minutes.");
        }

        // Generate 6-digit OTP (5 min expiry)
        String otp = String.format("%06d", new java.security.SecureRandom().nextInt(999999));
        jdbc.update(
            "UPDATE ecm_core.external_participants SET otp_code = ?, otp_expires_at = NOW() + INTERVAL '" +
            OTP_EXPIRY_MINUTES + " minutes', last_otp_ip = ?, failed_otp_attempts = 0 WHERE id = ?",
            otp, ipAddress, p.get("id"));

        // Publish OTP event
        publishCaseEvent("case.otp.requested", Map.of(
                "email", (String) p.get("email"),
                "otp", otp
        ));

        logExternalAccess((Integer) p.get("id"), (String) p.get("caseId"), "OTP_REQUESTED", ipAddress, null, null);
        log.info("OTP generated: participant={}, email={}, ip={}", p.get("id"), p.get("email"), ipAddress);
        return (String) p.get("email");
    }

    @Transactional
    public Map<String, Object> verifyOtpAndGetCase(UUID inviteToken, String otp, String ipAddress) {
        var p = loadParticipantByToken(inviteToken);
        Integer participantId = (Integer) p.get("id");
        String caseIdStr = (String) p.get("caseId");

        // Check expiry BEFORE value
        OffsetDateTime otpExpires = (OffsetDateTime) p.get("otpExpires");
        if (otpExpires != null && otpExpires.isBefore(OffsetDateTime.now())) {
            logExternalAccess(participantId, caseIdStr, "OTP_FAILED", ipAddress, null, "OTP expired");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "OTP expired. Request a new code.");
        }

        String storedOtp = (String) p.get("otpCode");
        if (storedOtp == null || storedOtp.isBlank())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No OTP generated. Request a code first.");

        if (!java.security.MessageDigest.isEqual(
                otp.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                storedOtp.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            // Increment failed attempts
            jdbc.update("""
                UPDATE ecm_core.external_participants
                SET failed_otp_attempts = COALESCE(failed_otp_attempts, 0) + 1
                WHERE id = ?
                """, participantId);
            logExternalAccess(participantId, caseIdStr, "OTP_FAILED", ipAddress, null, "Wrong OTP");
            Integer attempts = jdbc.queryForObject(
                    "SELECT failed_otp_attempts FROM ecm_core.external_participants WHERE id = ?",
                    Integer.class, participantId);
            int remaining = MAX_OTP_ATTEMPTS - (attempts != null ? attempts : 0);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Invalid OTP. " + (remaining > 0 ? remaining + " attempts remaining." : "Account locked."));
        }

        // OTP valid — clear it (one-time use) and create session token
        String sessionToken = externalSessionService.createToken(participantId, caseIdStr, ipAddress);

        jdbc.update("""
            UPDATE ecm_core.external_participants
            SET otp_code = NULL, otp_expires_at = NULL, failed_otp_attempts = 0,
                last_accessed_at = NOW(), session_token = ?, session_ip = ?,
                session_expires_at = NOW() + INTERVAL '60 minutes'
            WHERE id = ?
            """, sessionToken, ipAddress, participantId);

        logExternalAccess(participantId, caseIdStr, "OTP_VERIFIED", ipAddress, null, null);
        logExternalAccess(participantId, caseIdStr, "SESSION_CREATED", ipAddress, null, "1 hour session");

        UUID caseId = UUID.fromString(caseIdStr);

        // Build case view
        ExternalCaseView caseView = buildExternalCaseView(participantId, caseId, p);

        // Return session token + case view
        Map<String, Object> result = new HashMap<>();
        result.put("sessionToken", sessionToken);
        result.put("caseView", caseView);
        return result;
    }

    /** Validate session token for subsequent requests (upload, comment) */
    public ExternalSessionService.SessionClaims validateSession(String sessionToken, String ipAddress) {
        if (sessionToken == null || sessionToken.isBlank())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Session token required");

        ExternalSessionService.SessionClaims claims = externalSessionService.validate(sessionToken, ipAddress);
        if (claims == null)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired session. Please log in again.");

        // Verify participant is still active
        Integer active = jdbc.queryForObject(
                "SELECT CASE WHEN is_active THEN 1 ELSE 0 END FROM ecm_core.external_participants WHERE id = ?",
                Integer.class, claims.participantId());
        if (active == null || active == 0)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access has been revoked");

        return claims;
    }

    private ExternalCaseView buildExternalCaseView(Integer participantId, UUID caseId, Map<String, Object> participant) {
        CaseResponse caseResp = getById(caseId);

        List<SharedDocument> shared = jdbc.query("""
            SELECT cds.case_document_id, cd.status, d.name AS doc_name, pdt.name AS doc_type_name, cd.document_id
            FROM ecm_core.case_document_shares cds
            JOIN ecm_core.case_documents cd ON cd.id = cds.case_document_id
            LEFT JOIN ecm_core.documents d ON d.id = cd.document_id
            JOIN ecm_admin.product_document_types pdt ON pdt.id = cd.product_document_type_id
            WHERE cds.participant_id = ? AND cds.case_id = ?
            """, (rs, rowNum) -> new SharedDocument(
                rs.getInt("case_document_id"),
                rs.getString("doc_name"),
                rs.getString("doc_type_name"),
                rs.getString("status"),
                rs.getObject("document_id") != null ? UUID.fromString(rs.getString("document_id")) : null
            ), participantId, caseId);

        List<ExternalUploadDto> uploads = jdbc.query("""
            SELECT eu.id, eu.original_filename, eu.file_size_bytes, eu.description, eu.uploaded_at, eu.document_id
            FROM ecm_core.external_uploads eu
            WHERE eu.participant_id = ? AND eu.case_id = ?
            ORDER BY eu.uploaded_at DESC
            """, (rs, rowNum) -> new ExternalUploadDto(
                rs.getInt("id"),
                rs.getString("original_filename"),
                rs.getObject("file_size_bytes") != null ? rs.getLong("file_size_bytes") : null,
                rs.getString("description"),
                rs.getObject("uploaded_at", OffsetDateTime.class),
                rs.getObject("document_id") != null ? UUID.fromString(rs.getString("document_id")) : null,
                null, null
            ), participantId, caseId);

        return new ExternalCaseView(
                caseId, caseResp.productName(), caseResp.partyDisplayName(), caseResp.status(),
                (String) participant.get("name"), (String) participant.get("role"),
                shared, uploads);
    }

    private ParticipantResponse getParticipantByEmail(UUID caseId, String email) {
        return jdbc.query("""
            SELECT id, case_id, name, email, organization, role, phone,
                   invite_token, token_expires_at, last_accessed_at, invited_by, is_active, created_at
            FROM ecm_core.external_participants
            WHERE case_id = ? AND email = ?
            """, (rs, rowNum) -> mapParticipant(rs), caseId, email).get(0);
    }

    private ParticipantResponse mapParticipant(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ParticipantResponse(
                rs.getInt("id"),
                UUID.fromString(rs.getString("case_id")),
                rs.getString("name"),
                rs.getString("email"),
                rs.getString("organization"),
                rs.getString("role"),
                rs.getString("phone"),
                rs.getObject("invite_token") != null ? UUID.fromString(rs.getString("invite_token")) : null,
                rs.getObject("token_expires_at", OffsetDateTime.class),
                rs.getObject("last_accessed_at", OffsetDateTime.class),
                rs.getString("invited_by"),
                rs.getBoolean("is_active"),
                rs.getObject("created_at", OffsetDateTime.class)
        );
    }

    @Transactional(readOnly = true)
    public List<ExternalUploadDto> listExternalUploads(UUID caseId) {
        return jdbc.query("""
            SELECT eu.id, eu.original_filename, eu.file_size_bytes, eu.description, eu.uploaded_at,
                   eu.document_id, ep.name AS participant_name, ep.role AS participant_role
            FROM ecm_core.external_uploads eu
            JOIN ecm_core.external_participants ep ON ep.id = eu.participant_id
            WHERE eu.case_id = ?
            ORDER BY eu.uploaded_at DESC
            """, (rs, rowNum) -> new ExternalUploadDto(
                rs.getInt("id"),
                rs.getString("original_filename"),
                rs.getObject("file_size_bytes") != null ? rs.getLong("file_size_bytes") : null,
                rs.getString("description"),
                rs.getObject("uploaded_at", OffsetDateTime.class),
                rs.getObject("document_id") != null ? UUID.fromString(rs.getString("document_id")) : null,
                rs.getString("participant_name"),
                rs.getString("participant_role")
            ), caseId);
    }

    // ── External Upload + Comment (session-based) ──────────────────────────

    @Transactional
    public ExternalUploadDto externalUpload(String sessionToken, String ipAddress,
                                             org.springframework.web.multipart.MultipartFile file,
                                             String description) {
        var claims = validateSession(sessionToken, ipAddress);
        UUID caseId = UUID.fromString(claims.caseId());
        int participantId = claims.participantId();

        // Get participant name
        String participantName = jdbc.queryForObject(
                "SELECT name FROM ecm_core.external_participants WHERE id = ?",
                String.class, participantId);

        String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload.bin";
        long size = file.getSize();

        // Get party external ID
        String partyExternalId = null;
        try {
            partyExternalId = jdbc.queryForObject(
                    "SELECT p.external_id FROM ecm_core.cases c JOIN ecm_core.parties p ON p.id = c.party_id WHERE c.id = ?",
                    String.class, caseId);
        } catch (Exception e) {
            log.warn("Could not resolve party for case {}: {}", caseId, e.getMessage());
        }

        // Store file to MinIO
        UUID documentId = null;
        try {
            byte[] fileBytes = file.getBytes();
            String displayName = "External: " + filename + " (by " + participantName + ")";
            documentId = documentPromotionClient.promote(
                    fileBytes, filename, displayName, participantName, partyExternalId, null);
        } catch (Exception e) {
            log.error("Failed to store external upload: {}", e.getMessage());
        }

        jdbc.update("""
            INSERT INTO ecm_core.external_uploads (case_id, participant_id, original_filename, file_size_bytes, description, document_id)
            VALUES (?, ?, ?, ?, ?, ?)
            """, caseId, participantId, filename, size, description, documentId);

        recordTimelineEvent(caseId, "CHECKLIST_ITEM_UPLOADED",
                "External upload by " + participantName + ": " + filename, description, participantName);

        logExternalAccess(participantId, claims.caseId(), "FILE_UPLOADED", ipAddress, null, filename);

        Integer uploadId = jdbc.queryForObject(
                "SELECT id FROM ecm_core.external_uploads WHERE case_id = ? AND participant_id = ? ORDER BY uploaded_at DESC LIMIT 1",
                Integer.class, caseId, participantId);

        return new ExternalUploadDto(uploadId, filename, size, description, OffsetDateTime.now(),
                documentId, participantName, null);
    }

    @Transactional
    public void addExternalComment(String sessionToken, String ipAddress, String comment) {
        var claims = validateSession(sessionToken, ipAddress);
        UUID caseId = UUID.fromString(claims.caseId());

        String name = jdbc.queryForObject(
                "SELECT name FROM ecm_core.external_participants WHERE id = ?",
                String.class, claims.participantId());

        addNote(caseId, new AddNoteRequest(comment), name + " (external)");
        logExternalAccess(claims.participantId(), claims.caseId(), "COMMENT_ADDED", ipAddress, null, null);
        log.info("External comment: caseId={}, by={}", caseId, name);
    }

    /** Load participant by access token with all security fields */
    private Map<String, Object> loadParticipantByToken(UUID inviteToken) {
        var rows = jdbc.query("""
            SELECT id, case_id, email, name, role, token_expires_at, is_active,
                   otp_code, otp_expires_at, failed_otp_attempts, locked_until
            FROM ecm_core.external_participants WHERE invite_token = ?::uuid
            """, (rs, rowNum) -> {
                Map<String, Object> row = new HashMap<>();
                row.put("id", rs.getInt("id"));
                row.put("caseId", rs.getString("case_id"));
                row.put("email", rs.getString("email"));
                row.put("name", rs.getString("name"));
                row.put("role", rs.getString("role"));
                row.put("tokenExpiresAt", rs.getObject("token_expires_at", OffsetDateTime.class));
                row.put("isActive", rs.getBoolean("is_active"));
                row.put("otpCode", rs.getString("otp_code") != null ? rs.getString("otp_code") : "");
                row.put("otpExpires", rs.getObject("otp_expires_at", OffsetDateTime.class));
                row.put("failedOtpAttempts", rs.getInt("failed_otp_attempts"));
                row.put("lockedUntil", rs.getObject("locked_until", OffsetDateTime.class));
                return row;
            }, inviteToken);

        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Invalid access link");

        var p = rows.get(0);
        if (!Boolean.TRUE.equals(p.get("isActive")))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access has been revoked");
        OffsetDateTime tokenExpires = (OffsetDateTime) p.get("tokenExpiresAt");
        if (tokenExpires != null && tokenExpires.isBefore(OffsetDateTime.now()))
            throw new ResponseStatusException(HttpStatus.GONE, "Access link has expired");

        return p;
    }

    /** Log external access event for audit */
    private void logExternalAccess(Integer participantId, String caseId, String eventType,
                                    String ipAddress, String userAgent, String detail) {
        try {
            jdbc.update("""
                INSERT INTO ecm_core.external_access_log (participant_id, case_id, event_type, ip_address, user_agent, detail)
                VALUES (?, ?::uuid, ?, ?, ?, ?)
                """, participantId, caseId, eventType, ipAddress, userAgent, detail);
        } catch (Exception e) {
            log.warn("Failed to log external access: {}", e.getMessage());
        }
    }

    /** Publish a case event to RabbitMQ for ecm-notification to consume */
    private void publishCaseEvent(String routingKey, Map<String, String> payload) {
        try {
            rabbitTemplate.convertAndSend("ecm.admin", routingKey, payload);
            log.debug("Case event published: routingKey={}", routingKey);
        } catch (Exception e) {
            log.warn("Failed to publish case event {}: {} — email will not be sent", routingKey, e.getMessage());
        }
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
