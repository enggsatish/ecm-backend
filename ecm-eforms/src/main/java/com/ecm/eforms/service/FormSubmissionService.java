package com.ecm.eforms.service;

import com.ecm.common.client.DocumentPromotionClient;
import com.ecm.eforms.service.FormDocumentCreationService;
import com.ecm.eforms.event.FormEventPublisher;
import com.ecm.eforms.model.dto.EFormsDtos.*;
import com.ecm.eforms.model.entity.FormDefinition;
import com.ecm.eforms.model.entity.FormSubmission;
import com.ecm.eforms.repository.FormSubmissionRepository;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Full form submission lifecycle.
 *
 * Submit flow (normal mode):
 *   1. Resolve PUBLISHED form definition
 *   2. If draft=true → persist as DRAFT and return
 *   3. Validate submission_data via FormValidationService
 *   4. Persist with formSchemaSnapshot (point-in-time compliance copy)
 *   5. Generate draft PDF via PdfGenerationService
 *   6. If docuSignConfig.requiresSignature → create DocuSign envelope (stub)
 *   7. If NEITHER docuSignConfig.requiresSignature NOR workflowConfig is set →
 *      nothing will ever act on this submission otherwise, so promote the PDF
 *      straight to a document (status=APPROVED) and return — same as dev-mode.
 *   8. Otherwise, publish FormSubmittedEvent to RabbitMQ → ecm-workflow
 *
 * Submit flow (dev-mode: ecm.eforms.dev-mode=true):
 *   Steps 1–5 are identical.
 *   Step 6  → SKIPPED (no DocuSign envelope created)
 *   Step 7  → PDF stored to MinIO + document row inserted + status set to APPROVED
 *           → RabbitMQ event NOT published (document already exists; workflow not needed)
 *
 * Dev-mode intent:
 *   Allows developers to test the full form-fill → document-appears-in-list flow
 *   without a live DocuSign account or a configured review workflow.
 *   The generated PDF includes a "DEV MODE — Auto-Approved" watermark line.
 *   Set ECM_EFORMS_DEV_MODE=false (or ecm.eforms.dev-mode=false) before deploying
 *   to any shared/staging environment.
 *
 * Party linkage:
 *   SubmitFormRequest.partyExternalId → FormSubmission.partyExternalId (stored in DB)
 *   → copied to ecm_core.documents.party_external_id by WorkflowCompletedListener
 *     (normal mode) or directly here (dev mode).
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class FormSubmissionService {

    private static final String TENANT = "default";

    private final FormSubmissionRepository   submissionRepo;
    private final FormDefinitionService      definitionService;
    private final FormValidationService      validationService;
    private final PdfGenerationService       pdfService;
    private final DocuSignService            docuSignService;
    private final FormEventPublisher         eventPublisher;
    private final FormDocumentCreationService documentCreationService;

    private final DocumentPromotionClient documentPromotionClient;

    // ── Dev mode flag ──────────────────────────────────────────────────────────
    @Value("${ecm.eforms.dev-mode:false}")
    private boolean devMode;

    // ── Submit / Save Draft ────────────────────────────────────────────────────

    public FormSubmission submit(SubmitFormRequest req,
                                 String userId, String userName,
                                 String ipAddress, String userAgent) {

        // 1. Resolve definition
        FormDefinition def = req.getFormVersion() != null
                ? definitionService.getByFormKeyAndVersion(req.getFormKey(), req.getFormVersion())
                : definitionService.getPublishedByFormKey(req.getFormKey());

        // 2. Build submission — partyExternalId wired in here
        FormSubmission sub = FormSubmission.builder()
                .tenantId(TENANT)
                .formDefinition(def)
                .formKey(def.getFormKey())
                .formVersion(def.getVersion())
                .formSchemaSnapshot(def.getSchema())
                .submissionData(req.getSubmissionData())
                .partyExternalId(req.getPartyExternalId())   // ← party linkage
                .status("DRAFT")
                .submittedBy(userId)
                .submittedByName(userName)
                .channel(req.getChannel() != null ? req.getChannel() : "WEB")
                .ipAddress(ipAddress)
                .userAgent(userAgent)
                .build();

        // 3. Draft save — persist partyExternalId on draft too so it survives resume
        if (req.isDraft()) {
            if (req.getExistingSubmissionId() != null) {
                FormSubmission existing = submissionRepo.findById(req.getExistingSubmissionId())
                        .orElseThrow(() -> new EntityNotFoundException("Draft not found"));
                if (!"DRAFT".equals(existing.getStatus()))
                    throw new IllegalStateException("Submission is no longer a draft");
                existing.setSubmissionData(req.getSubmissionData());
                existing.setPartyExternalId(req.getPartyExternalId());   // ← update party on re-save
                FormSubmission saved = submissionRepo.save(existing);
                log.info("Draft updated: id={}", saved.getId());
                return saved;
            }
            FormSubmission saved = submissionRepo.save(sub);
            log.info("Draft saved: id={}, formKey={}", saved.getId(), saved.getFormKey());
            return saved;
        }

        // 4. Validate
        FormValidationService.ValidationResult validation =
                validationService.validate(def.getSchema(), req.getSubmissionData());

        if (!validation.valid()) {
            throw new FormValidationException("Form validation failed",
                    validation.fieldErrors(), validation.formErrors());
        }

        // 5. Mark submitted and persist
        sub.markSubmitted(userId, userName);
        FormSubmission saved = submissionRepo.save(sub);

        // 6. Generate PDF
        byte[] pdfBytes = null;
        try {
            pdfBytes = pdfService.generate(saved);
            log.debug("PDF generated: {} bytes for submissionId={}", pdfBytes.length, saved.getId());
        } catch (PdfGenerationService.PdfGenerationException e) {
            log.warn("PDF generation failed for {}: {}", saved.getId(), e.getMessage());
        }

        // ── DEV MODE: bypass DocuSign + workflow, create document immediately ──
        if (devMode) {
            log.info("[DEV MODE] Processing submissionId={}", saved.getId());
            if (pdfBytes != null) {
                boolean requiresSignature = def.getDocuSignConfig() != null
                        && def.getDocuSignConfig().isRequiresSignature();

                if (requiresSignature) {
                    // Simulate DocuSign: stub envelope → immediately sign
                    String stubEnvelopeId = "DEV-AUTO-SIGN-" + UUID.randomUUID();
                    saved.markPendingSignature(stubEnvelopeId);
                    saved = submissionRepo.save(saved);
                    log.info("[DEV MODE] Auto-sign: stubEnvelopeId={}", stubEnvelopeId);
                    UUID docId = promoteSubmissionDocument(saved, pdfBytes);
                    if (docId != null) {
                        saved.markSigned(docId);
                        saved = submissionRepo.save(saved);
                    }
                } else {
                    promoteSubmissionDocument(saved, pdfBytes);
                }
            } else {
                log.warn("[DEV MODE] PDF null — marking APPROVED without document");
                saved.setStatus("APPROVED");
                saved.setReviewedAt(OffsetDateTime.now());
                saved.setReviewNotes("DEV MODE — auto-approved (PDF generation failed)");
                submissionRepo.save(saved);
            }
            return submissionRepo.findById(saved.getId()).orElse(saved);
        }

        // ── NORMAL MODE: DocuSign → RabbitMQ ──────────────────────────────────

        // 7. DocuSign (stub until credentials are configured)
        if (def.getDocuSignConfig() != null && def.getDocuSignConfig().isRequiresSignature()) {
            try {
                var signing = new DocuSignService.SigningRequest(
                        req.getSignerEmail(), req.getSignerName(),
                        req.getEmailSubjectOverride(), req.getEmailBodyOverride());
                String envelopeId = docuSignService.createEnvelope(saved, signing);
                saved.markPendingSignature(envelopeId);
                saved = submissionRepo.save(saved);
                log.info("DocuSign envelope: id={}, submissionId={}", envelopeId, saved.getId());
            } catch (Exception e) {
                log.error("DocuSign failed for {}: {}", saved.getId(), e.getMessage());
            }
        }

        // 8. No lifecycle configured (no signature, no workflow) — nothing will ever
        //    consume a SUBMITTED event or resolve this submission otherwise, so promote
        //    the document immediately instead of leaving it stranded. Same promotion
        //    helper dev-mode uses, just applied as the correct default here too.
        boolean requiresSignature = def.getDocuSignConfig() != null
                && def.getDocuSignConfig().isRequiresSignature();
        boolean hasWorkflow = def.getWorkflowConfig() != null;

        if (!requiresSignature && !hasWorkflow) {
            if (pdfBytes != null) {
                UUID docId = promoteSubmissionDocument(saved, pdfBytes);
                if (docId != null) {
                    saved.setStatus("APPROVED");
                    saved.setSignedDocumentId(docId);
                    saved.setReviewedAt(OffsetDateTime.now());
                    saved.setReviewNotes("Auto-completed — no signature or workflow configured for this form");
                    saved = submissionRepo.save(saved);
                }
            } else {
                log.warn("No-lifecycle submission {} has no PDF — marking APPROVED without document", saved.getId());
                saved.setStatus("APPROVED");
                saved.setReviewedAt(OffsetDateTime.now());
                saved.setReviewNotes("Auto-approved (PDF generation failed)");
                saved = submissionRepo.save(saved);
            }
            log.info("No-lifecycle submission auto-completed: id={}, formKey={}", saved.getId(), saved.getFormKey());
            return saved;
        }

        // 9. Publish event → triggers workflow in ecm-workflow
        //    Skip for case-linked submissions — case manages its own review flow.
        if (!req.isSkipWorkflow()) {
            eventPublisher.publishSubmitted(saved, def);
        } else {
            log.info("Workflow trigger skipped for submission={} (case-linked form)", saved.getId());
        }

        log.info("Submitted: id={}, formKey={}, status={}", saved.getId(), saved.getFormKey(), saved.getStatus());
        return saved;
    }

    // ── Document promotion helper ─────────────────────────────────────────────

    /**
     * Promote a submission's generated PDF straight to ecm_core.documents,
     * bypassing DocuSign/workflow. Used by dev-mode (bypasses them for local
     * testing) and by the no-lifecycle path in {@link #submit} (bypasses them
     * because there's genuinely nothing configured to wait for).
     *
     * Uses the same INSERT pattern as WorkflowCompletedListener.handleApproved()
     * to keep all three paths consistent.
     */
    private UUID promoteSubmissionDocument(FormSubmission submission, byte[] pdfBytes) {

        // Build document name
        String docName = submission.getFormKey() + " — "
                + (submission.getSubmittedByName() != null
                ? submission.getSubmittedByName()
                : submission.getSubmittedBy());

        UUID documentId = documentPromotionClient.promote(
                pdfBytes, submission.getFormKey() + "-" + submission.getId() + ".pdf", submission.getFormKey(),
                submission.getSubmittedBy(),
                submission.getPartyExternalId(),   // ← bug fix: was not passed before
                null);

        log.info("[DEV MODE] Document Promoted: id={}, name={}, party={}",
                documentId, docName, submission.getPartyExternalId());

        return documentId;
    }

    // ── Read ───────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public FormSubmission getById(UUID id) {
        return submissionRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Submission not found: " + id));
    }

    /**
     * Regenerate the submission's PDF on demand from its persisted formSchemaSnapshot +
     * submissionData. Not read from storage — cheap, in-memory, always available
     * regardless of submission status (draft PDF was never persisted as raw bytes
     * outside of the promote-to-document paths).
     */
    @Transactional(readOnly = true)
    public byte[] getPdf(UUID id) {
        FormSubmission sub = getById(id);
        return pdfService.generate(sub);
    }

    /**
     * Manually upload a signed copy for a submission that requires a signature —
     * covers the branch walk-in case: print, sign by hand, scan, upload here instead
     * of going through DocuSign. Branches on what's configured for the form:
     *   - workflow + signature required: promote the document, fire the same
     *     "signed" event DocuSign completion fires ({@link FormEventPublisher#publishSigned}),
     *     which resumes any workflow instance paused at the docuSignWait receive-task —
     *     the listener on the other end doesn't care how signing happened.
     *   - signature required, no workflow: promote the document directly to APPROVED,
     *     same as the no-lifecycle path in {@link #submit} — nothing else is waiting
     *     on this submission once it's signed.
     */
    public FormSubmission uploadSignedCopy(UUID id, byte[] fileBytes, String originalFilename, String uploadedBy) {
        FormSubmission sub = getById(id);
        FormDefinition def = sub.getFormDefinition();

        boolean requiresSignature = def.getDocuSignConfig() != null
                && def.getDocuSignConfig().isRequiresSignature();
        if (!requiresSignature) {
            throw new IllegalStateException("This form does not require a signature");
        }

        String ext = originalFilename != null && originalFilename.contains(".")
                ? originalFilename.substring(originalFilename.lastIndexOf('.'))
                : ".pdf";
        String filename = sub.getFormKey() + "-" + sub.getId() + "-signed" + ext;

        UUID docId = documentPromotionClient.promote(
                fileBytes, filename, sub.getFormKey(),
                sub.getSubmittedBy(), sub.getPartyExternalId(), null);

        sub.markSigned(docId);

        boolean hasWorkflow = def.getWorkflowConfig() != null;
        if (hasWorkflow) {
            sub = submissionRepo.save(sub);
            eventPublisher.publishSigned(sub);
            log.info("Manual signed copy uploaded by {}, workflow notified: submissionId={}, documentId={}",
                    uploadedBy, sub.getId(), docId);
        } else {
            sub.setStatus("APPROVED");
            sub.setReviewedAt(OffsetDateTime.now());
            sub.setReviewNotes("Auto-completed — signature uploaded manually by " + uploadedBy
                    + ", no workflow configured for this form");
            sub = submissionRepo.save(sub);
            log.info("Manual signed copy uploaded by {}, auto-completed (no workflow): submissionId={}, documentId={}",
                    uploadedBy, sub.getId(), docId);
        }

        return sub;
    }

    @Transactional(readOnly = true)
    public Page<FormSubmission> listForUser(String userId, Pageable pageable) {
        return submissionRepo.findByTenantIdAndSubmittedByOrderByCreatedAtDesc(TENANT, userId, pageable);
    }

    @Transactional(readOnly = true)
    public Page<FormSubmission> listAll(String status, String formKey, String assignedTo, Pageable pageable) {
        return submissionRepo.findAllWithFilters(TENANT, status, formKey, assignedTo, pageable);
    }

    // Review operations are now handled exclusively through the Flowable workflow engine.
    // See: /review/documents → EcmTaskService.approve() → processCompletedListener
    //      → workflow.completed → WorkflowCompletedListener → createFromApprovedSubmission()

    // ── Withdraw ───────────────────────────────────────────────────────────────

    public FormSubmission withdraw(UUID id, String userId) {
        FormSubmission sub = getById(id);
        if (!sub.getSubmittedBy().equals(userId))
            throw new SecurityException("Only the submitter can withdraw this submission");
        if (List.of("APPROVED", "COMPLETED", "REJECTED").contains(sub.getStatus()))
            throw new IllegalStateException("Cannot withdraw a " + sub.getStatus() + " submission");

        if (sub.getDocuSignEnvelopeId() != null) {
            try { docuSignService.voidEnvelope(sub.getDocuSignEnvelopeId(), "Withdrawn by submitter"); }
            catch (Exception e) { log.warn("Failed to void envelope on withdraw: {}", e.getMessage()); }
        }

        sub.setStatus("WITHDRAWN");
        return submissionRepo.save(sub);
    }

    // ── Validation exception ───────────────────────────────────────────────────

    public static class FormValidationException extends RuntimeException {
        private final Map<String, List<String>> fieldErrors;
        private final List<String>              formErrors;

        public FormValidationException(String msg,
                                       Map<String, List<String>> fe, List<String> foe) {
            super(msg);
            this.fieldErrors = fe;
            this.formErrors  = foe;
        }

        public Map<String, List<String>> getFieldErrors() { return fieldErrors; }
        public List<String>              getFormErrors()   { return formErrors; }
    }
}