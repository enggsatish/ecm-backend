package com.ecm.eforms.controller;

import com.ecm.common.model.ApiResponse;
import com.ecm.eforms.event.FormEventPublisher;
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

    @PostMapping("/webhook")
    public ResponseEntity<ApiResponse<String>> receiveWebhook(
            @RequestBody String rawBody,
            @RequestHeader(value = "X-DocuSign-Signature-1", required = false) String hmacSig) {

        log.info("[DocuSign Webhook] Event received");

        try {
            // PRODUCTION: uncomment before go-live
            // validateHmac(rawBody, hmacSig);

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
     * envelope-completed: mark the submission as SIGNED.
     *
     * Sprint 2 stub: generates a placeholder UUID for the signed document.
     * Sprint 3 TODO: download actual PDF bytes from DocuSign and store in MinIO,
     *               then pass the real MinIO document UUID to markSigned().
     */
    private void handleCompleted(FormSubmission sub) {
        log.info("[DocuSign] envelope-completed: submissionId={}", sub.getId());
        // STUB — replace with real MinIO UUID after downloading from DocuSign
        UUID stubDocId = UUID.randomUUID();
        sub.markSigned(stubDocId);          // sets status=SIGNED, docuSignStatus=completed, signedDocumentId
        submissionRepo.save(sub);
        eventPublisher.publishSigned(sub);  // notifies ecm-workflow → moves instance to SIGNED status
    }

    private void handleDeclined(FormSubmission sub, Map<String, Object> payload) {
        log.info("[DocuSign] envelope-declined: submissionId={}", sub.getId());
        sub.setStatus("SIGN_DECLINED");
        sub.setDocuSignStatus("declined");
        submissionRepo.save(sub);
        String reason = extractString(payload, "declineReason");
        eventPublisher.publishSignDeclined(sub, reason != null ? reason : "No reason provided");
    }

    private void handleVoided(FormSubmission sub) {
        log.info("[DocuSign] envelope-voided: submissionId={}", sub.getId());
        sub.setDocuSignStatus("voided");
        submissionRepo.save(sub);
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

    private void markProcessed(DocuSignEvent event, String error) {
        event.setProcessed(error == null);
        event.setError(error);
        eventRepo.save(event);
    }

    // ── HMAC Validation (enable before production) ────────────────────────────
    //
    // @Autowired DocuSignProperties props;
    //
    // private void validateHmac(String body, String signature) throws Exception {
    //     if (signature == null || signature.isBlank())
    //         throw new SecurityException("Missing X-DocuSign-Signature-1");
    //     Mac mac = Mac.getInstance("HmacSHA256");
    //     mac.init(new SecretKeySpec(
    //         props.getWebhookSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    //     String expected = Base64.getEncoder()
    //         .encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    //     if (!MessageDigest.isEqual(expected.getBytes(), signature.getBytes()))
    //         throw new SecurityException("Invalid DocuSign HMAC signature");
    // }
}
