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
 *   7. Publish FormSubmittedEvent to RabbitMQ → ecm-workflow
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
                    UUID docId = createDocumentInDevMode(saved, pdfBytes);
                    if (docId != null) {
                        saved.markSigned(docId);
                        saved = submissionRepo.save(saved);
                    }
                } else {
                    createDocumentInDevMode(saved, pdfBytes);
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
                String envelopeId = docuSignService.createEnvelope(saved);
                saved.markPendingSignature(envelopeId);
                saved = submissionRepo.save(saved);
                log.info("DocuSign envelope: id={}, submissionId={}", envelopeId, saved.getId());
            } catch (Exception e) {
                log.error("DocuSign failed for {}: {}", saved.getId(), e.getMessage());
            }
        }

        // 8. Publish event → triggers workflow in ecm-workflow
        eventPublisher.publishSubmitted(saved, def);

        log.info("Submitted: id={}, formKey={}, status={}", saved.getId(), saved.getFormKey(), saved.getStatus());
        return saved;
    }

    // ── Dev mode helper ────────────────────────────────────────────────────────

    /**
     * Dev-mode fast path:
     *   1. Store PDF to MinIO
     *   2. INSERT into ecm_core.documents (cross-schema via JdbcTemplate)
     *   3. Update FormSubmission: status=APPROVED, signed_document_id=new doc UUID
     *
     * Uses the same INSERT pattern as WorkflowCompletedListener.handleApproved()
     * to keep both paths consistent. The PDF has a dummy signature line
     * (already rendered by PdfGenerationService).
     */
    private UUID createDocumentInDevMode(FormSubmission submission, byte[] pdfBytes) {

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

    @Transactional(readOnly = true)
    public Page<FormSubmission> listForUser(String userId, Pageable pageable) {
        return submissionRepo.findByTenantIdAndSubmittedByOrderByCreatedAtDesc(TENANT, userId, pageable);
    }

    @Transactional(readOnly = true)
    public Page<FormSubmission> listAll(String status, String formKey, String assignedTo, Pageable pageable) {
        return submissionRepo.findAllWithFilters(TENANT, status, formKey, assignedTo, pageable);
    }

    @Transactional(readOnly = true)
    public List<FormSubmission> getReviewQueue() {
        return submissionRepo.findReviewQueue(TENANT);
    }

    // ── Review ─────────────────────────────────────────────────────────────────

    public FormSubmission review(UUID id, ReviewSubmissionRequest req, String reviewerId) {
        FormSubmission sub = getById(id);
        String newStatus = req.getStatus().toUpperCase();
        validateTransition(sub.getStatus(), newStatus);

        sub.setStatus(newStatus);
        sub.setReviewNotes(req.getReviewNotes());
        sub.setReviewedBy(reviewerId);
        sub.setReviewedAt(OffsetDateTime.now());

        if (req.getAssignedTo() != null && !req.getAssignedTo().isBlank()) {
            sub.setAssignedTo(req.getAssignedTo());
            sub.setAssignedAt(OffsetDateTime.now());
        }

        FormSubmission updated = submissionRepo.save(sub);
        eventPublisher.publishReviewed(updated);
        log.info("Reviewed: id={}, newStatus={}, by={}", id, newStatus, reviewerId);

        // ── Document promotion on direct approval ────────────────────────────
        // When backoffice approves via the Review Queue UI, the workflow task
        // path (WorkflowCompletedListener → createFromApprovedSubmission) is
        // never triggered. This means the approved form never lands in the
        // document list unless we also promote here.
        //
        // Both paths must create a document on APPROVED so the behaviour is
        // consistent regardless of whether the form had a workflow attached:
        //
        //   Workflow path:  EcmTaskService.approve() → processCompletedListener
        //                   → workflow.completed event → WorkflowCompletedListener
        //                   → createFromApprovedSubmission()
        //
        //   Direct review:  FormSubmissionService.review() [THIS METHOD]
        //                   → createFromApprovedSubmission()
        //
        // Best-effort: failure here does NOT roll back the APPROVED status.
        // The form stays approved; only the document promotion is missing.
        // Operators can re-trigger via admin or re-upload manually if needed.
        if ("APPROVED".equals(newStatus)) {
            try {
                documentCreationService.createFromApprovedSubmission(updated);
            } catch (Exception ex) {
                log.error("Document promotion failed for approved submissionId={}: {}",
                        id, ex.getMessage(), ex);
                // Do not rethrow — APPROVED status is already committed
            }
        }

        return updated;
    }

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

    // ── Status transition guard ────────────────────────────────────────────────

    private void validateTransition(String current, String next) {
        boolean ok = switch (current) {
            case "SUBMITTED", "SIGNED" -> List.of("IN_REVIEW", "APPROVED", "REJECTED").contains(next);
            case "IN_REVIEW"           -> List.of("IN_REVIEW", "APPROVED", "REJECTED").contains(next);
            default -> false;
        };
        if (!ok) throw new IllegalStateException("Invalid transition: " + current + " → " + next);
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