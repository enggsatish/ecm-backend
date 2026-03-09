package com.ecm.eforms.service;

import com.ecm.common.client.DocumentPromotionClient;
import com.ecm.eforms.model.entity.FormDefinition;
import com.ecm.eforms.model.entity.FormSubmission;
import com.ecm.eforms.repository.FormDefinitionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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

    /**
     * Generate a PDF for the approved submission and push it to ecm-document.
     *
     * @param submission the APPROVED FormSubmission (status already set by caller)
     * @return UUID of the newly created Document, or null if promotion failed
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID createFromApprovedSubmission(FormSubmission submission) {
        log.info("SK: Creating form document from approved form submission: {}", submission);
        try {
            // 1. Build a human-friendly display name for the document
            String formName  = resolveFormName(submission);
            String shortId   = submission.getId().toString().substring(0, 8);
            String displayName = formName + " — " + shortId;
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
                    null                                 // categoryId — null until form→category mapping added
            );

            if (documentId != null) {
                log.info("Document promoted for FormSubmission {}: documentId={}",
                        submission.getId(), documentId);
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