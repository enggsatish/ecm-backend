package com.ecm.eforms.service;

import com.ecm.common.client.DocumentPromotionClient;
import com.ecm.eforms.model.entity.FormDefinition;
import com.ecm.eforms.model.entity.FormSubmission;
import com.ecm.eforms.repository.FormDefinitionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Promotes an approved FormSubmission to a Document record in ecm-document.
 *
 * Called by WorkflowCompletedListener when a form-triggered workflow ends
 * with decision=APPROVED. The promoted document appears in the document list
 * alongside uploaded and scanned documents.
 *
 * Strategy:
 *   1. Re-generate the PDF via PdfGenerationService (stateless, always works,
 *      no MinIO dependency for fetching the draft PDF).
 *   2. Call DocumentPromotionClient.promote() — internal HTTP to ecm-document
 *      POST /api/documents/upload. This fires OCR, OpenSearch indexing, and
 *      audit log exactly as a normal upload would.
 *
 * Transaction:
 *   REQUIRES_NEW so document promotion runs independently of the caller's
 *   transaction. If promotion fails, the FormSubmission status update (APPROVED)
 *   is NOT rolled back — the form is still approved; only the document is missing.
 *   Operators can re-trigger via admin if needed. This avoids a stuck state where
 *   repeated message delivery keeps re-approving the same form.
 *
 * Why PdfGenerationService instead of fetching from MinIO?
 *   - PdfGenerationService is stateless (just needs the submission entity).
 *   - The MinIO draft PDF path is not stored on FormSubmission; recovering it
 *     would require extra storage/path logic. Re-generating is simpler and safe.
 *   - For signed workflows the signed PDF (signedDocumentId) would be the ideal
 *     source, but that requires a MinIO fetch. That can be added in a later sprint.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FormDocumentCreationService {

    private final PdfGenerationService    pdfService;
    private final DocumentPromotionClient documentPromotionClient;
    private final FormDefinitionRepository definitionRepository;
    private final JdbcTemplate            jdbc;
    private final ObjectMapper            objectMapper;

    /**
     * Generate a PDF for the approved submission and push it to ecm-document.
     *
     * @param submission the APPROVED FormSubmission (status already set by caller)
     * @return UUID of the newly created Document, or null if promotion failed
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID createFromApprovedSubmission(FormSubmission submission) {
        try {
            // 1. Build a human-friendly display name for the document
            //    Format: {formName} — {customerName or customerRef} — {date}
            String formName    = resolveFormName(submission);
            String customerCtx = resolveCustomerContext(submission);
            String dateStr     = java.time.LocalDate.now().toString();
            String displayName = formName + " — " + customerCtx + " — " + dateStr;
            String filename    = submission.getFormKey() + "-" + submission.getId() + ".pdf";

            // 2. Re-generate PDF from submission data
            byte[] pdfBytes;
            try {
                pdfBytes = pdfService.generate(submission);
            } catch (PdfGenerationService.PdfGenerationException e) {
                log.error("PDF generation failed for submissionId={}: {}",
                        submission.getId(), e.getMessage(), e);
                return null;
            }

            // 3. Promote to ecm-document via internal HTTP call
            //    DocumentPromotionClient.promote() calls POST /api/documents/upload
            //    with X-Internal-Service: ecm-eforms header, triggering OCR +
            //    OpenSearch indexing + audit exactly as a normal upload.
            UUID documentId = documentPromotionClient.promote(
                    pdfBytes,
                    filename,
                    displayName,
                    submission.getSubmittedBy(),         // uploaded_by_email on the document
                    submission.getPartyExternalId(),     // soft ref → party
                    submission.getFormDefinition() != null
                            ? submission.getFormDefinition().getDocumentCategoryId()
                            : null,
                    true   // eformGenerated — category/fields already known from the submission
            );

            if (documentId != null) {
                log.info("Document promoted for FormSubmission {}: documentId={}",
                        submission.getId(), documentId);

                // Copy form submission data directly into the document's extracted_fields.
                writeSubmissionDataToDocument(documentId, submission);

                // Auto-link to case checklist if case context was provided
                linkToCaseChecklist(documentId, submission);
            } else {
                log.error("DocumentPromotionClient returned null for FormSubmission {} — " +
                        "document NOT created. Check ecm-document logs.", submission.getId());
            }

            return documentId;

        } catch (Exception ex) {
            // Swallow: caller uses REQUIRES_NEW so the APPROVED status is already committed.
            // Log a clear error so operators can investigate.
            log.error("createFromApprovedSubmission failed for FormSubmission {}: {}",
                    submission.getId(), ex.getMessage(), ex);
            return null;
        }
    }

    /**
     * Auto-links the created document to a case checklist item if the submission
     * contains _caseId and _checklistItemId in its submission_data.
     * These are injected by FormFillPage when filling a form from a case context.
     */
    private void linkToCaseChecklist(UUID documentId, FormSubmission submission) {
        try {
            Map<String, Object> data = submission.getSubmissionData();
            if (data == null) return;

            Object caseIdObj = data.get("_caseId");
            Object itemIdObj = data.get("_checklistItemId");

            if (caseIdObj == null || itemIdObj == null) return;

            String caseId = caseIdObj.toString();
            int itemId = Integer.parseInt(itemIdObj.toString());

            int rows = jdbc.update("""
                UPDATE ecm_core.case_documents
                SET document_id = ?, status = 'UPLOADED', uploaded_at = NOW(), updated_at = NOW()
                WHERE id = ? AND case_id = ?::uuid
                """, documentId, itemId, caseId);

            if (rows > 0) {
                log.info("Auto-linked document {} to case {} checklist item {}", documentId, caseId, itemId);
            }
        } catch (Exception e) {
            log.warn("Failed to auto-link document to case checklist: {}", e.getMessage());
        }
    }

    /**
     * Writes the form submission data directly into the document's extracted_fields column.
     *
     * For form-generated documents, this is more accurate than OCR extraction because
     * the structured data is already available — no need to regex-parse a PDF we created.
     *
     * The OCR pipeline will still run (extracting raw text from the PDF), but the
     * extracted_fields are already populated from this method. If OCR also extracts fields
     * (via a category template), they will overwrite these — which is fine since OCR
     * fields from a system-generated PDF should match the submission data.
     */
    private void writeSubmissionDataToDocument(UUID documentId, FormSubmission submission) {
        try {
            Map<String, Object> submissionData = submission.getSubmissionData();
            if (submissionData == null || submissionData.isEmpty()) {
                log.debug("No submission data to write for documentId={}", documentId);
                return;
            }

            String fieldsJson = objectMapper.writeValueAsString(submissionData);

            jdbc.update("""
                UPDATE ecm_core.documents
                SET extracted_fields = ?::jsonb,
                    updated_at = NOW()
                WHERE id = ?
                """, fieldsJson, documentId);

            log.info("Wrote {} submission fields to document {}", submissionData.size(), documentId);
        } catch (Exception e) {
            // Best-effort — don't fail the promotion if field copy fails
            log.warn("Failed to write submission data to document {}: {}", documentId, e.getMessage());
        }
    }

    /**
     * Updates a document's status directly via SQL.
     * Used to set PENDING_SIGNATURE on documents created for eSign workflows.
     */
    public void updateDocumentStatus(UUID documentId, String newStatus) {
        jdbc.update("""
            UPDATE ecm_core.documents
            SET status = ?, updated_at = NOW()
            WHERE id = ?
            """, newStatus, documentId);
        log.info("Document {} status updated to {}", documentId, newStatus);
    }

    /**
     * Resolve customer context for the document display name.
     * Tries: party display name from DB → party external ID → submitter name → short ID fallback.
     */
    private String resolveCustomerContext(FormSubmission submission) {
        // Try to get customer display name from parties table
        String partyExtId = submission.getPartyExternalId();
        if (partyExtId != null && !partyExtId.isBlank()) {
            try {
                String displayName = jdbc.queryForObject(
                        "SELECT display_name FROM ecm_core.parties WHERE external_id = ?",
                        String.class, partyExtId);
                if (displayName != null) return displayName;
            } catch (Exception e) {
                log.debug("Could not resolve party name for {}: {}", partyExtId, e.getMessage());
            }
            return partyExtId; // fallback to external ID
        }

        // Fallback to submitter name or short submission ID
        if (submission.getSubmittedByName() != null && !submission.getSubmittedByName().isBlank()) {
            return submission.getSubmittedByName();
        }
        return submission.getId().toString().substring(0, 8);
    }

    /**
     * Resolve a display-friendly form name from the submission's linked definition,
     * falling back to the formKey if the definition is not loaded.
     */
    private String resolveFormName(FormSubmission submission) {
        // Try the already-loaded association first (avoids an extra query)
        FormDefinition def = submission.getFormDefinition();
        if (def != null && def.getName() != null) {
            return def.getName();
        }
        // Fallback: look up by tenantId + formKey + version (matches actual repo signature)
        try {
            String tenantId = submission.getTenantId() != null ? submission.getTenantId() : "default";
            return definitionRepository
                    .findByTenantIdAndFormKeyAndVersion(tenantId, submission.getFormKey(), submission.getFormVersion())
                    .map(FormDefinition::getName)
                    .orElse(submission.getFormKey());
        } catch (Exception e) {
            log.debug("Could not resolve form name for key={}: {}", submission.getFormKey(), e.getMessage());
            return submission.getFormKey();
        }
    }
}