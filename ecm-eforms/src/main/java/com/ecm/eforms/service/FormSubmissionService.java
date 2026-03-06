package com.ecm.eforms.service;

import com.ecm.eforms.event.FormEventPublisher;
import com.ecm.eforms.model.dto.EFormsDtos.*;
import com.ecm.eforms.model.entity.FormDefinition;
import com.ecm.eforms.model.entity.FormSubmission;
import com.ecm.eforms.repository.FormSubmissionRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Full form submission lifecycle.
 *
 * Submit flow:
 *   1. Resolve PUBLISHED form definition
 *   2. If draft=true → persist as DRAFT and return
 *   3. Validate submission_data via FormValidationService
 *   4. Persist with formSchemaSnapshot (point-in-time compliance copy)
 *   5. Generate draft PDF via PdfGenerationService
 *   6. If docuSignConfig.requiresSignature → create DocuSign envelope (stub)
 *   7. Publish FormSubmittedEvent to RabbitMQ → ecm-workflow
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class FormSubmissionService {

    private static final String TENANT = "default";

    private final FormSubmissionRepository submissionRepo;
    private final FormDefinitionService    definitionService;
    private final FormValidationService    validationService;
    private final PdfGenerationService     pdfService;
    private final DocuSignService          docuSignService;
    private final FormEventPublisher       eventPublisher;

    // ── Submit / Save Draft ───────────────────────────────────────────

    public FormSubmission submit(SubmitFormRequest req,
                                  String userId, String userName,
                                  String ipAddress, String userAgent) {

        // 1. Resolve definition
        FormDefinition def = req.getFormVersion() != null
            ? definitionService.getByFormKeyAndVersion(req.getFormKey(), req.getFormVersion())
            : definitionService.getPublishedByFormKey(req.getFormKey());

        // 2. Build submission
        FormSubmission sub = FormSubmission.builder()
            .tenantId(TENANT)
            .formDefinition(def)
            .formKey(def.getFormKey())
            .formVersion(def.getVersion())
            .formSchemaSnapshot(def.getSchema())   // compliance snapshot
            .submissionData(req.getSubmissionData())
            .status("DRAFT")
            .submittedBy(userId)
            .submittedByName(userName)
            .channel(req.getChannel() != null ? req.getChannel() : "WEB")
            .ipAddress(ipAddress)
            .userAgent(userAgent)
            .build();

        // 3. Draft save — return immediately
        if (req.isDraft()) {
            if (req.getExistingSubmissionId() != null) {
                FormSubmission existing = submissionRepo.findById(req.getExistingSubmissionId())
                        .orElseThrow(() -> new EntityNotFoundException("Draft not found"));
                if (!"DRAFT".equals(existing.getStatus()))
                    throw new IllegalStateException("Submission is no longer a draft");
                existing.setSubmissionData(req.getSubmissionData());
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

        // 6. Generate draft PDF (Sprint 1: bytes not stored; Sprint 2: stored to MinIO)
        try {
            byte[] pdf = pdfService.generate(saved);
            log.debug("Draft PDF: {} bytes for submissionId={}", pdf.length, saved.getId());
            // Sprint 2: UUID docId = minioService.store(...); saved.setDraftDocumentId(docId);
        } catch (PdfGenerationService.PdfGenerationException e) {
            log.warn("Draft PDF generation failed for {}: {}", saved.getId(), e.getMessage());
        }

        // 7. DocuSign (stub in Sprint 1)
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

        // 8. Publish event
        eventPublisher.publishSubmitted(saved, def);

        log.info("Submitted: id={}, formKey={}, status={}", saved.getId(), saved.getFormKey(), saved.getStatus());
        return saved;
    }

    // ── Read ──────────────────────────────────────────────────────────

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

    // ── Review ────────────────────────────────────────────────────────

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
        return updated;
    }

    // ── Withdraw ──────────────────────────────────────────────────────

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

    // ── Status transition guard ───────────────────────────────────────

    private void validateTransition(String current, String next) {
        boolean ok = switch (current) {
            case "SUBMITTED", "SIGNED" -> List.of("IN_REVIEW","APPROVED","REJECTED").contains(next);
            case "IN_REVIEW"           -> List.of("IN_REVIEW","APPROVED","REJECTED").contains(next);
            default -> false;
        };
        if (!ok) throw new IllegalStateException("Invalid transition: " + current + " → " + next);
    }

    // ── Validation exception ──────────────────────────────────────────

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
