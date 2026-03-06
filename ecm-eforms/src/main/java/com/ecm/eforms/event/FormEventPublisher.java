package com.ecm.eforms.event;

import com.ecm.eforms.config.EFormsRabbitConfig;
import com.ecm.eforms.model.entity.FormDefinition;
import com.ecm.eforms.model.entity.FormSubmission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Publishes form lifecycle events to RabbitMQ (ecm.eforms topic exchange).
 *
 * Consumers:
 *   ecm-workflow    → listens on form.submitted, form.signed, form.sign.declined
 *   ecm-notification → listens on form.reviewed
 *
 * Publishing is non-blocking: transaction is NOT rolled back if messaging fails.
 * Failed events are logged and can be replayed from the audit log.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FormEventPublisher {

    private final RabbitTemplate rabbit;

    /** Published after successful form submission (triggers workflow creation) */
    public void publishSubmitted(FormSubmission sub, FormDefinition def) {
        Map<String, Object> event = new HashMap<>();
        event.put("eventType",           "FORM_SUBMITTED");
        event.put("submissionId",         sub.getId());
        event.put("formKey",              sub.getFormKey());
        event.put("formVersion",          sub.getFormVersion());
        event.put("tenantId",             sub.getTenantId());
        event.put("submittedBy",          sub.getSubmittedBy());
        event.put("submittedByName",      sub.getSubmittedByName());
        event.put("submittedAt",          sub.getSubmittedAt());
        event.put("workflowDefinitionKey",
            def.getWorkflowConfig() != null ? def.getWorkflowConfig().getWorkflowDefinitionKey() : null);
        event.put("defaultPriority",
            def.getWorkflowConfig() != null ? def.getWorkflowConfig().getDefaultPriority() : "NORMAL");
        event.put("timestamp",            OffsetDateTime.now());
        publish(EFormsRabbitConfig.RK_SUBMITTED, event);
    }

    /** Published when DocuSign reports envelope-completed */
    public void publishSigned(FormSubmission sub) {
        Map<String, Object> event = new HashMap<>();
        event.put("eventType",       "FORM_SIGNED");
        event.put("submissionId",     sub.getId());
        event.put("formKey",          sub.getFormKey());
        event.put("envelopeId",       sub.getDocuSignEnvelopeId());
        event.put("signedDocumentId", sub.getSignedDocumentId());
        event.put("completedAt",      sub.getDocuSignCompletedAt());
        event.put("timestamp",        OffsetDateTime.now());
        publish(EFormsRabbitConfig.RK_SIGNED, event);
    }

    /** Published when DocuSign reports envelope-declined */
    public void publishSignDeclined(FormSubmission sub, String reason) {
        Map<String, Object> event = new HashMap<>();
        event.put("eventType",    "FORM_SIGN_DECLINED");
        event.put("submissionId",  sub.getId());
        event.put("formKey",       sub.getFormKey());
        event.put("envelopeId",    sub.getDocuSignEnvelopeId());
        event.put("declineReason", reason);
        event.put("timestamp",     OffsetDateTime.now());
        publish(EFormsRabbitConfig.RK_DECLINED, event);
    }

    /** Published after backoffice review (approved / rejected) */
    public void publishReviewed(FormSubmission sub) {
        Map<String, Object> event = new HashMap<>();
        event.put("eventType",    "FORM_REVIEWED");
        event.put("submissionId",  sub.getId());
        event.put("formKey",       sub.getFormKey());
        event.put("status",        sub.getStatus());
        event.put("reviewedBy",    sub.getReviewedBy());
        event.put("submittedBy",   sub.getSubmittedBy());
        event.put("reviewNotes",   sub.getReviewNotes());
        event.put("timestamp",     OffsetDateTime.now());
        publish(EFormsRabbitConfig.RK_REVIEWED, event);
    }

    // ── Internal ──────────────────────────────────────────────────────

    private void publish(String routingKey, Object payload) {
        try {
            rabbit.convertAndSend(EFormsRabbitConfig.EXCHANGE, routingKey, payload);
            log.debug("Published event: exchange={}, routingKey={}", EFormsRabbitConfig.EXCHANGE, routingKey);
        } catch (Exception e) {
            log.error("Failed to publish event to {}/{}: {}", EFormsRabbitConfig.EXCHANGE, routingKey, e.getMessage());
        }
    }
}
