package com.ecm.eforms.controller;

import com.ecm.common.model.ApiResponse;
import com.ecm.eforms.event.FormEventPublisher;
import com.ecm.eforms.service.DocuSignService;
import com.ecm.eforms.model.entity.DocuSignEvent;
import com.ecm.eforms.model.entity.FormSubmission;
import com.ecm.eforms.repository.DocuSignEventRepository;
import com.ecm.eforms.repository.FormSubmissionRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Receives DocuSign Connect webhook events.
 *
 * This endpoint is PUBLICLY accessible (no JWT) — both the gateway RouteConfig
 * and EFormsSecurityConfig have it in the permitAll() list.
 * Security is provided by HMAC validation of the X-DocuSign-Signature-1 header.
 * (HMAC validation is stubbed — enable before going to production.)
 *
 * Two-phase processing:
 *   Phase 1: Persist raw event immediately (idempotency guard + audit trail).
 *   Phase 2: Process the event synchronously.
 *
 * Always returns HTTP 200 — DocuSign retries on non-2xx.
 */
@RestController
@RequestMapping("/api/eforms/docusign")
@RequiredArgsConstructor
@Slf4j
public class DocuSignWebhookController {

    private final DocuSignEventRepository  eventRepo;
    private final FormSubmissionRepository submissionRepo;
    private final FormEventPublisher       eventPublisher;
    private final ObjectMapper             objectMapper;
    private final DocuSignService          docuSignService;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    private final com.ecm.common.client.DocumentPromotionClient documentPromotionClient;

    @PostMapping("/webhook")
    public ResponseEntity<ApiResponse<String>> receiveWebhook(
            @RequestBody String rawBody,
            @RequestHeader(value = "X-DocuSign-Signature-1", required = false) String hmacSig) {

        log.info("[DocuSign Webhook] Event received");

        try {
            // HMAC signature validation — enforced when webhook_hmac_secret is configured
            docuSignService.validateWebhookHmac(
                    rawBody.getBytes(java.nio.charset.StandardCharsets.UTF_8), hmacSig);

            // Phase 1: parse → idempotency check → persist raw event
            Map<String, Object> payload = objectMapper.readValue(rawBody, new TypeReference<>() {});
            String envelopeId = extractString(payload, "envelopeId");
            String eventType  = extractString(payload, "event");

            log.info("[DocuSign Webhook] envelopeId={}, eventType={}", envelopeId, eventType);

            // Idempotency: skip duplicate event types for the same envelope
            // (DocuSign may retry if it didn't receive a 200 in time)
            if (envelopeId != null && eventType != null
                    && eventRepo.existsByEnvelopeIdAndEventType(envelopeId, eventType)) {
                log.info("[DocuSign Webhook] Duplicate suppressed: envelopeId={}, eventType={}", envelopeId, eventType);
                return ResponseEntity.ok(ApiResponse.ok("duplicate-suppressed"));
            }

            DocuSignEvent event = eventRepo.save(DocuSignEvent.builder()
                    .envelopeId(envelopeId != null ? envelopeId : "UNKNOWN-" + UUID.randomUUID())
                    .eventType(eventType)
                    .rawPayload(payload)
                    .processed(false)
                    .build());

            // Phase 2: process
            processEvent(event);

        } catch (SecurityException se) {
            // HMAC validation failed — log clearly but still return 200
            // (DocuSign retries on non-2xx, flooding logs)
            log.warn("[DocuSign Webhook] HMAC validation failed: {} — skipping event. " +
                     "Check webhook_hmac_secret matches DocuSign Connect config.", se.getMessage());
        } catch (Exception e) {
            log.error("[DocuSign Webhook] Error: {}", e.getMessage(), e);
            // Return 200 regardless — raw payload was persisted for manual replay
        }

        return ResponseEntity.ok(ApiResponse.ok("received"));
    }

    // ── Event dispatch ────────────────────────────────────────────────────────

    private void processEvent(DocuSignEvent event) {
        try {
            Optional<FormSubmission> opt = submissionRepo.findByDocuSignEnvelopeId(event.getEnvelopeId());
            if (opt.isEmpty()) {
                log.warn("[DocuSign Webhook] No submission for envelopeId={}", event.getEnvelopeId());
                markProcessed(event, null);
                return;
            }

            FormSubmission sub = opt.get();
            String et = event.getEventType() != null ? event.getEventType().toLowerCase() : "";

            switch (et) {
                case "envelope-completed" -> handleCompleted(sub);
                case "envelope-declined"  -> handleDeclined(sub, event.getRawPayload());
                case "envelope-voided"    -> handleVoided(sub);
                default -> log.info("[DocuSign Webhook] Unhandled eventType={}", et);
            }

            markProcessed(event, null);

        } catch (Exception e) {
            log.error("[DocuSign Webhook] Processing failed for event {}: {}", event.getId(), e.getMessage(), e);
            event.setError(e.getMessage());
            eventRepo.save(event);
        }
    }

    // ── Handlers ──────────────────────────────────────────────────────────────

    /**
     * envelope-completed: download signed PDF from DocuSign, replace the unsigned
     * document in MinIO/ecm-document, and mark submission SIGNED.
     *
     * Flow:
     *   1. Download signed PDF from DocuSign
     *   2. Find existing document record (created during workflow approval)
     *   3. Replace the PDF in ecm-document (re-upload via DocumentPromotionClient)
     *   4. Update document status to SIGNED + signedAt timestamp
     *   5. Mark FormSubmission as SIGNED
     */
    private void handleCompleted(FormSubmission sub) {
        log.info("[DocuSign] envelope-completed: submissionId={}, envelopeId={}",
                sub.getId(), sub.getDocuSignEnvelopeId());

        // Step 1: Find existing document created during workflow approval
        UUID existingDocId = findDocumentForSubmission(sub.getId());
        log.info("[DocuSign] Found existing document: {}", existingDocId);

        // Step 2: Download signed PDF from DocuSign and replace
        byte[] signedPdf = null;
        try {
            signedPdf = docuSignService.downloadSignedDocument(sub.getDocuSignEnvelopeId());
            if (signedPdf != null && signedPdf.length > 0) {
                log.info("[DocuSign] Signed PDF downloaded: {} bytes", signedPdf.length);

                if (existingDocId != null) {
                    replaceDocumentPdf(existingDocId, signedPdf, sub);
                    log.info("[DocuSign] Replaced document {} with signed PDF", existingDocId);
                } else {
                    // No existing document — promote signed PDF as new document
                    String filename = sub.getFormKey() + "-signed-" + sub.getId() + ".pdf";
                    String displayName = "Signed: " + (sub.getFormKey() != null ? sub.getFormKey() : "Document");
                    existingDocId = documentPromotionClient.promote(
                            signedPdf, filename, displayName,
                            sub.getSubmittedBy(), sub.getPartyExternalId(),
                            sub.getFormDefinition() != null
                                    ? sub.getFormDefinition().getDocumentCategoryId() : null);
                    log.info("[DocuSign] Promoted signed PDF as new document: {}", existingDocId);
                }
            } else {
                log.warn("[DocuSign] Empty signed PDF from DocuSign for envelope {}",
                        sub.getDocuSignEnvelopeId());
            }
        } catch (Exception e) {
            log.error("[DocuSign] PDF download/replace failed for envelope {}: {}",
                    sub.getDocuSignEnvelopeId(), e.getMessage());
        }

        // Step 3: ALWAYS update document status to ACTIVE (signing complete)
        if (existingDocId != null) {
            updateDocumentStatusForSubmission(sub.getId(), "ACTIVE");
            log.info("[DocuSign] Document status updated to ACTIVE for submission {}", sub.getId());
        }

        // Step 4: Mark submission as SIGNED
        sub.markSigned(existingDocId != null ? existingDocId : UUID.randomUUID());
        submissionRepo.save(sub);
        eventPublisher.publishSigned(sub);

        // Step 5: Record case timeline event if this submission is linked to a case
        recordCaseTimelineEvent(sub, existingDocId);

        log.info("[DocuSign] Submission marked SIGNED: submissionId={}, signedDocId={}",
                sub.getId(), existingDocId);
    }

    /**
     * Find the document record that was created from this form submission.
     * The document is linked via the submission's form key and submitter email,
     * or directly through the case_documents table if case-linked.
     */
    private UUID findDocumentForSubmission(UUID submissionId) {
        try {
            // Look for a document whose extracted_fields contains this submission's data
            // or that was uploaded by the same email around the same time
            return jdbcTemplate.queryForObject("""
                SELECT d.id FROM ecm_core.documents d
                WHERE d.original_filename LIKE '%' || ? || '%'
                ORDER BY d.created_at DESC LIMIT 1
                """, UUID.class, submissionId.toString());
        } catch (Exception e) {
            log.debug("[DocuSign] No existing document found for submission {}", submissionId);
            return null;
        }
    }

    /**
     * Upload the signed PDF as a new version of the existing document.
     * Creates a v2 linked to the original unsigned v1 — preserving both versions.
     */
    private void replaceDocumentPdf(UUID documentId, byte[] signedPdf, FormSubmission sub) {
        try {
            String documentServiceUrl = "http://localhost:8082";
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA);
            headers.set("X-Internal-Service", "ecm-eforms");

            // Build multipart request with the signed PDF
            org.springframework.util.LinkedMultiValueMap<String, Object> body = new org.springframework.util.LinkedMultiValueMap<>();
            org.springframework.core.io.ByteArrayResource fileResource = new org.springframework.core.io.ByteArrayResource(signedPdf) {
                @Override public String getFilename() {
                    return sub.getFormKey() + "-signed-" + sub.getId() + ".pdf";
                }
            };
            org.springframework.http.HttpHeaders fileHeaders = new org.springframework.http.HttpHeaders();
            fileHeaders.setContentType(org.springframework.http.MediaType.APPLICATION_PDF);
            body.add("file", new org.springframework.http.HttpEntity<>(fileResource, fileHeaders));

            org.springframework.http.HttpEntity<org.springframework.util.MultiValueMap<String, Object>> request =
                    new org.springframework.http.HttpEntity<>(body, headers);

            new org.springframework.web.client.RestTemplate().postForEntity(
                    documentServiceUrl + "/api/documents/" + documentId + "/versions",
                    request, String.class);

            log.info("[DocuSign] Created signed version for document {} ({} bytes)", documentId, signedPdf.length);
        } catch (Exception e) {
            log.error("[DocuSign] Failed to create signed version for document {}: {}",
                    documentId, e.getMessage(), e);
        }
    }

    private void handleDeclined(FormSubmission sub, Map<String, Object> payload) {
        log.info("[DocuSign] envelope-declined: submissionId={}", sub.getId());
        sub.setStatus("SIGN_DECLINED");
        sub.setDocuSignStatus("declined");
        submissionRepo.save(sub);

        // Update linked document status
        updateDocumentStatusForSubmission(sub.getId(), "SIGN_DECLINED");

        String reason = extractString(payload, "declineReason");
        eventPublisher.publishSignDeclined(sub, reason != null ? reason : "No reason provided");
    }

    private void handleVoided(FormSubmission sub) {
        log.info("[DocuSign] envelope-voided: submissionId={}", sub.getId());
        sub.setDocuSignStatus("voided");
        submissionRepo.save(sub);

        // Reset document status back to ACTIVE (envelope cancelled)
        updateDocumentStatusForSubmission(sub.getId(), "ACTIVE");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private String extractString(Map<String, Object> map, String key) {
        if (map == null) return null;
        Object v = map.get(key);
        if (v != null) return v.toString();
        // Also check nested under "data" (DocuSign envelope JSON structure)
        Object data = map.get("data");
        if (data instanceof Map) {
            Object nested = ((Map<String, Object>) data).get(key);
            if (nested != null) return nested.toString();
        }
        return null;
    }

    // ── Internal API: test connection ──

    @GetMapping("/test-connection")
    public ResponseEntity<Map<String, Object>> testConnection() {
        try {
            boolean ok = docuSignService.testConnection();
            return ResponseEntity.ok(Map.of("success", ok,
                    "message", ok ? "JWT grant authenticated" : "Authentication failed"));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("success", false,
                    "message", "Test failed: " + e.getMessage()));
        }
    }

    // ── Internal API: create envelope (called by ecm-workflow DocuSignDelegate) ──

    @PostMapping("/create-envelope")
    public ResponseEntity<Map<String, String>> createEnvelope(@RequestBody Map<String, Object> req) {
        String submissionId   = str(req.get("submissionId"));
        String documentId     = str(req.get("documentId"));
        String recipientEmail = str(req.get("recipientEmail"));
        String recipientName  = str(req.get("recipientName"));
        String subject        = str(req.get("subject"));
        if (subject == null || subject.isBlank()) subject = "Please sign your document";

        // Signature placement options
        String placement      = str(req.get("placement"));       // "auto" | "lastPage" | "specific"
        String sigPage        = str(req.get("signaturePage"));
        String sigX           = str(req.get("signatureX"));
        String sigY           = str(req.get("signatureY"));
        boolean needInitials  = Boolean.TRUE.equals(req.get("requireInitials"));
        boolean needDate      = Boolean.TRUE.equals(req.get("requireDateSigned"));

        log.info("[DocuSign] create-envelope request: submissionId={}, docId={}, recipient={}, placement={}",
                submissionId, documentId, recipientEmail, placement);

        try {
            String envelopeId;

            if (submissionId != null && !submissionId.isBlank()) {
                // Form-based: look up the submission and create envelope from its PDF
                FormSubmission sub = submissionRepo.findById(UUID.fromString(submissionId))
                        .orElseThrow(() -> new IllegalArgumentException("Submission not found: " + submissionId));
                // recipientEmail/recipientName/subject were previously extracted but
                // silently dropped for the submission-based path — now threaded through.
                envelopeId = docuSignService.createEnvelope(sub,
                        new DocuSignService.SigningRequest(recipientEmail, recipientName, subject, null));

                // Update submission with envelope info
                sub.markPendingSignature(envelopeId);
                submissionRepo.save(sub);

                // Update linked document status to PENDING_SIGNATURE
                updateDocumentStatusForSubmission(sub.getId(), "PENDING_SIGNATURE");
            } else if (documentId != null && !documentId.isBlank()) {
                // Document-based: fetch PDF from ecm-document and send for signing
                byte[] pdfBytes = fetchDocumentPdf(UUID.fromString(documentId));
                if (pdfBytes == null || pdfBytes.length == 0) {
                    throw new IllegalStateException("Could not fetch PDF for document: " + documentId);
                }

                String docName = jdbcTemplate.queryForObject(
                        "SELECT name FROM ecm_core.documents WHERE id = ?",
                        String.class, UUID.fromString(documentId));

                if (recipientName == null || recipientName.isBlank()) {
                    recipientName = recipientEmail != null ? recipientEmail.split("@")[0] : "Signer";
                }

                envelopeId = docuSignService.createEnvelopeForDocument(
                        pdfBytes,
                        docName != null ? docName : "Document",
                        recipientEmail,
                        recipientName,
                        subject,
                        placement,
                        sigPage != null ? sigPage : "1",
                        sigX != null ? sigX : "100",
                        sigY != null ? sigY : "700",
                        needInitials,
                        needDate);

                // Update document status to PENDING_SIGNATURE
                jdbcTemplate.update("""
                    UPDATE ecm_core.documents
                    SET status = 'PENDING_SIGNATURE', updated_at = NOW()
                    WHERE id = ?
                    """, UUID.fromString(documentId));

                log.info("[DocuSign] Document envelope created: envelopeId={}, docId={}, signer={}",
                        envelopeId, documentId, recipientEmail);
            } else {
                throw new IllegalArgumentException("Either submissionId or documentId is required");
            }

            return ResponseEntity.ok(Map.of("envelopeId", envelopeId));

        } catch (Exception e) {
            log.error("[DocuSign] create-envelope failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Fetches a document's PDF bytes from ecm-document service.
     */
    private byte[] fetchDocumentPdf(UUID documentId) {
        try {
            String documentServiceUrl = "http://localhost:8082";
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.set("X-Internal-Service", "ecm-eforms");

            org.springframework.http.ResponseEntity<byte[]> response =
                    new org.springframework.web.client.RestTemplate().exchange(
                            documentServiceUrl + "/api/documents/" + documentId + "/download",
                            org.springframework.http.HttpMethod.GET,
                            new org.springframework.http.HttpEntity<>(headers),
                            byte[].class);

            return response.getBody();
        } catch (Exception e) {
            log.error("[DocuSign] Failed to fetch document PDF {}: {}", documentId, e.getMessage());
            return null;
        }
    }

    /**
     * Update the status of the document linked to a form submission.
     * Finds the document by matching the submission ID in the filename.
     */
    private void updateDocumentStatusForSubmission(UUID submissionId, String newStatus) {
        try {
            int rows = jdbcTemplate.update("""
                UPDATE ecm_core.documents
                SET status = ?::ecm_core.document_status, updated_at = NOW()
                WHERE original_filename LIKE '%' || ? || '%'
                """, newStatus, submissionId.toString());
            if (rows > 0) {
                log.info("[DocuSign] Document status updated to {} for submission {}", newStatus, submissionId);
            } else {
                log.debug("[DocuSign] No document found to update for submission {}", submissionId);
            }
        } catch (Exception e) {
            // status column may be varchar not enum — try without cast
            try {
                int rows = jdbcTemplate.update("""
                    UPDATE ecm_core.documents
                    SET status = ?, updated_at = NOW()
                    WHERE original_filename LIKE '%' || ? || '%'
                    """, newStatus, submissionId.toString());
                if (rows > 0) {
                    log.info("[DocuSign] Document status updated to {} for submission {}", newStatus, submissionId);
                }
            } catch (Exception e2) {
                log.warn("[DocuSign] Failed to update document status for submission {}: {}",
                        submissionId, e2.getMessage());
            }
        }
    }

    /**
     * Record a DOCUMENT_SIGNED timeline event on any case linked to this document.
     */
    private void recordCaseTimelineEvent(FormSubmission sub, UUID documentId) {
        if (documentId == null) return;
        try {
            // Find case linked to this document
            var caseRows = jdbcTemplate.queryForList("""
                SELECT case_id FROM ecm_core.case_documents WHERE document_id = ?
                """, documentId);

            for (var row : caseRows) {
                Object caseIdObj = row.get("case_id");
                if (caseIdObj == null) continue;
                UUID caseId = caseIdObj instanceof UUID uid ? uid : UUID.fromString(caseIdObj.toString());

                jdbcTemplate.update("""
                    INSERT INTO ecm_core.case_timeline_events
                        (case_id, event_type, description, detail, actor)
                    VALUES (?, 'DOCUMENT_SIGNED',
                            'Document signed via DocuSign',
                            ?, 'docusign')
                    """, caseId,
                    "envelopeId=" + sub.getDocuSignEnvelopeId() + ", documentId=" + documentId);

                log.info("[DocuSign] Timeline event recorded for caseId={}, documentId={}", caseId, documentId);
            }
        } catch (Exception e) {
            log.debug("[DocuSign] Timeline event recording failed (non-fatal): {}", e.getMessage());
        }
    }

    private void markProcessed(DocuSignEvent event, String error) {
        event.setProcessed(error == null);
        event.setError(error);
        eventRepo.save(event);
    }

    /** Safely convert Object to String (handles null, numbers, etc.) */
    private static String str(Object o) {
        if (o == null) return null;
        String s = o.toString().trim();
        return s.isEmpty() ? null : s;
    }

}
