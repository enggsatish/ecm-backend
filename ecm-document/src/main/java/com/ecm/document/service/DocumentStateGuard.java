package com.ecm.document.service;

import com.ecm.document.entity.Document;
import com.ecm.document.entity.DocumentStatus;
import com.ecm.document.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Validates document state before destructive or state-changing actions.
 * Prevents invalid transitions and concurrent conflicts.
 *
 * All guard methods throw IllegalStateException with a clear user-facing message
 * if the action is not allowed. The controller/service should call these
 * BEFORE performing the action.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentStateGuard {

    private final DocumentRepository documentRepo;
    private final JdbcTemplate jdbc;

    /**
     * Guard: Can this document be archived?
     * NOT allowed if: active workflow, pending DocuSign, linked to active case
     */
    public void assertCanArchive(UUID documentId) {
        Document doc = findOrThrow(documentId);

        if (doc.getStatus() == DocumentStatus.ARCHIVED) {
            throw new IllegalStateException("Document is already archived");
        }
        if (doc.getStatus() == DocumentStatus.DELETED || doc.getStatus() == DocumentStatus.PURGED) {
            throw new IllegalStateException("Cannot archive a deleted document");
        }

        assertNoActiveWorkflow(documentId, "archive");
        assertNoActiveCaseLink(documentId, "archive");
        assertNotLocked(doc, "archive");
    }

    /**
     * Guard: Can this document be soft-deleted?
     */
    public void assertCanDelete(UUID documentId) {
        Document doc = findOrThrow(documentId);

        if (doc.getStatus() == DocumentStatus.DELETED || doc.getStatus() == DocumentStatus.PURGED) {
            throw new IllegalStateException("Document is already deleted");
        }

        assertNoActiveWorkflow(documentId, "delete");
        assertNoActiveCaseLink(documentId, "delete");
        assertNotLocked(doc, "delete");
    }

    /**
     * Guard: Can this document be sent for DocuSign?
     */
    public void assertCanSendForSignature(UUID documentId) {
        Document doc = findOrThrow(documentId);

        if (doc.getStatus() != DocumentStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Only ACTIVE documents can be sent for signature. Current status: " + doc.getStatus());
        }

        assertNoPendingEnvelope(documentId);
        assertNotLocked(doc, "send for signature");
    }

    /**
     * Guard: Can a workflow be started for this document?
     */
    public void assertCanStartWorkflow(UUID documentId) {
        assertNoActiveWorkflow(documentId, "start another workflow");
    }

    /**
     * Guard: Can this user modify this document?
     *
     * If the document is linked to an active case, only the case assignee/claimer
     * can modify it. Documents not linked to any active case follow normal permissions.
     *
     * @param userEmail the email of the user attempting the action
     * @param action description for error message (e.g., "checkout", "delete")
     */
    public void assertCanModify(UUID documentId, String userEmail, String action) {
        CaseOwnership ownership = findCaseOwnership(documentId);

        if (ownership == null) {
            // Not linked to any active case — normal permissions apply
            return;
        }

        // ADMIN / SUPER_ADMIN can always modify case-linked documents
        if (isAdmin(userEmail)) {
            return;
        }

        String caseOwner = ownership.claimedBy != null ? ownership.claimedBy : ownership.assignedTo;
        String caseGroup = ownership.assignedToGroup;

        if (caseOwner != null && caseOwner.equals(userEmail)) {
            return; // User is the direct assignee or claimer
        }

        // If assigned to group but unclaimed, allow any authenticated user
        // (group membership is enforced at the controller level via @PreAuthorize)
        if (caseOwner == null && caseGroup != null) {
            return; // Unclaimed group task — allow any group member
        }

        throw new IllegalStateException(
                "Cannot " + action + " — document is linked to case " + ownership.caseRef +
                " which is assigned to " + (caseOwner != null ? caseOwner : "group: " + caseGroup) +
                ". Only the case assignee can modify linked documents.");
    }

    /**
     * Get case ownership info for a document.
     * Returns null if document is not linked to any active case.
     */
    public CaseOwnership findCaseOwnership(UUID documentId) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT c.id as case_id, c.status,
                       c.assigned_to, c.assigned_to_name, c.assigned_to_group,
                       c.claimed_by, c.claimed_by_name,
                       COALESCE(c.assigned_to_name, c.claimed_by_name, c.assigned_to_group, 'Unassigned') as case_ref
                FROM ecm_core.case_documents cd
                JOIN ecm_core.cases c ON c.id = cd.case_id
                WHERE cd.document_id = ?
                AND c.status NOT IN ('COMPLETED', 'APPROVED', 'REJECTED', 'CANCELLED')
                LIMIT 1
                """, documentId);

            if (rows.isEmpty()) return null;

            Map<String, Object> row = rows.get(0);
            return new CaseOwnership(
                    row.get("case_id") != null ? UUID.fromString(row.get("case_id").toString()) : null,
                    str(row.get("status")),
                    str(row.get("assigned_to")),
                    str(row.get("assigned_to_name")),
                    str(row.get("assigned_to_group")),
                    str(row.get("claimed_by")),
                    str(row.get("claimed_by_name")),
                    str(row.get("case_ref"))
            );
        } catch (Exception e) {
            log.debug("Case ownership check skipped for {}: {}", documentId, e.getMessage());
            return null;
        }
    }

    /**
     * Check if the user has ADMIN or SUPER_ADMIN role.
     * Uses cross-schema query to ecm_core.users + user_roles + roles.
     */
    private boolean isAdmin(String userEmail) {
        if (userEmail == null) return false;
        try {
            Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM ecm_core.user_roles ur
                JOIN ecm_core.users u ON u.id = ur.user_id
                JOIN ecm_core.roles r ON r.id = ur.role_id
                WHERE u.email = ? AND r.name IN ('ECM_ADMIN', 'ECM_SUPER_ADMIN')
                """, Integer.class, userEmail);
            return count != null && count > 0;
        } catch (Exception e) {
            log.debug("Admin check failed for {}: {}", userEmail, e.getMessage());
            return false;
        }
    }

    private static String str(Object o) {
        return o != null ? o.toString() : null;
    }

    public record CaseOwnership(
            UUID caseId, String caseStatus,
            String assignedTo, String assignedToName, String assignedToGroup,
            String claimedBy, String claimedByName,
            String caseRef
    ) {}

    // ── Internal checks ──────────────────────────────────────────────────────

    private void assertNoActiveWorkflow(UUID documentId, String action) {
        try {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ecm_workflow.workflow_instance_records " +
                    "WHERE document_id = ? AND status = 'ACTIVE'",
                    Integer.class, documentId);
            if (count != null && count > 0) {
                throw new IllegalStateException(
                        "Cannot " + action + " — document has " + count + " active workflow(s). " +
                        "Complete or cancel the workflow first.");
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.debug("Workflow check skipped: {}", e.getMessage());
        }
    }

    private void assertNoActiveCaseLink(UUID documentId, String action) {
        try {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ecm_core.case_documents cd " +
                    "JOIN ecm_core.cases c ON c.id = cd.case_id " +
                    "WHERE cd.document_id = ? " +
                    "AND c.status NOT IN ('COMPLETED', 'APPROVED', 'REJECTED', 'CANCELLED')",
                    Integer.class, documentId);
            if (count != null && count > 0) {
                throw new IllegalStateException(
                        "Cannot " + action + " — document is linked to " + count + " active case(s). " +
                        "Complete or cancel the case first.");
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.debug("Case link check skipped: {}", e.getMessage());
        }
    }

    private void assertNoPendingEnvelope(UUID documentId) {
        try {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ecm_forms.form_submissions " +
                    "WHERE docusign_envelope_id IS NOT NULL " +
                    "AND docusign_status IN ('sent', 'delivered') " +
                    "AND id::text IN (SELECT submission_id::text FROM ecm_workflow.workflow_instance_records " +
                    "                  WHERE document_id = ?)",
                    Integer.class, documentId);
            if (count != null && count > 0) {
                throw new IllegalStateException(
                        "Cannot send for signature — document already has a pending DocuSign envelope. " +
                        "Wait for the current signing to complete.");
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.debug("DocuSign envelope check skipped: {}", e.getMessage());
        }
    }

    private void assertNotLocked(Document doc, String action) {
        if (doc.getLockedBy() != null && doc.getLockExpiresAt() != null
                && doc.getLockExpiresAt().isAfter(java.time.Instant.now())) {
            throw new IllegalStateException(
                    "Cannot " + action + " — document is checked out by " + doc.getLockedBy() +
                    ". Ask them to release it or wait for the lock to expire.");
        }
    }

    private Document findOrThrow(UUID documentId) {
        return documentRepo.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + documentId));
    }
}
