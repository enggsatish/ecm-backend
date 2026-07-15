package com.ecm.workflow.flowable;

import com.ecm.workflow.config.WorkflowRabbitConfig;
import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Flowable service-task delegate for DOCUSIGN workflow steps.
 *
 * Referenced in BPMN via: flowable:delegateExpression="${docuSignDelegate}"
 *
 * Flow:
 *   1. Reads process variables (documentId, submissionId, recipientEmail)
 *   2. Calls ecm-eforms to create a DocuSign envelope
 *   3. Stores envelopeId as process variable
 *   4. Publishes notification
 *   5. Workflow continues to next element (should be a receive task / signal wait)
 *
 * The signing happens asynchronously. When DocuSign webhook fires (envelope-completed),
 * ecm-eforms publishes a RabbitMQ event that ecm-workflow consumes to signal the
 * waiting receive task, resuming the workflow.
 */
@Slf4j
@Component("docuSignDelegate")
public class DocuSignDelegate implements JavaDelegate {

    private final RabbitTemplate rabbitTemplate;

    @Value("${ecm.eforms-service-url:http://localhost:8084}")
    private String eformsServiceUrl;

    // Flowable field injection from BPMN extensionElements
    private org.flowable.common.engine.api.delegate.Expression subjectTemplate;
    private org.flowable.common.engine.api.delegate.Expression recipientEmailVar;

    public DocuSignDelegate(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    public void execute(DelegateExecution execution) {
        String processInstanceId = execution.getProcessInstanceId();
        String submissionId = safeVar(execution, "submissionId");
        String documentId   = safeVar(execution, "documentId");
        String submittedBy  = safeVar(execution, "submittedBy");

        // Resolve recipient email
        String emailVarName = recipientEmailVar != null
                ? String.valueOf(recipientEmailVar.getValue(execution)) : "submittedBy";
        String recipientEmail = safeVar(execution, emailVarName);
        if (recipientEmail.isBlank()) recipientEmail = submittedBy;

        String subject = subjectTemplate != null
                ? String.valueOf(subjectTemplate.getValue(execution))
                : "Please sign your document";

        log.info("[DocuSignDelegate] Creating envelope: processInstance={}, submission={}, recipient={}",
                processInstanceId, submissionId, recipientEmail);

        try {
            // Call ecm-eforms to create the envelope
            String envelopeId = callCreateEnvelope(submissionId, documentId, recipientEmail, subject);

            execution.setVariable("docuSignEnvelopeId", envelopeId);
            execution.setVariable("docuSignStatus", "sent");

            log.info("[DocuSignDelegate] Envelope created: envelopeId={}, processInstance={}",
                    envelopeId, processInstanceId);

            // Notify — "Document sent for signature"
            try {
                Map<String, Object> event = new HashMap<>();
                event.put("eventType", "DOCUSIGN_SENT");
                event.put("processInstanceId", processInstanceId);
                event.put("recipientEmail", recipientEmail);
                event.put("envelopeId", envelopeId);
                event.put("submittedBy", submittedBy);
                event.put("timestamp", OffsetDateTime.now());
                rabbitTemplate.convertAndSend(
                        WorkflowRabbitConfig.NOTIFICATIONS_EXCHANGE,
                        "notification.email", event);
            } catch (Exception e) {
                log.warn("[DocuSignDelegate] Notification failed: {}", e.getMessage());
            }

        } catch (Exception e) {
            log.error("[DocuSignDelegate] Envelope creation failed: processInstance={}, error={}",
                    processInstanceId, e.getMessage(), e);
            execution.setVariable("docuSignError", e.getMessage());
            execution.setVariable("docuSignStatus", "failed");
        }
    }

    private String callCreateEnvelope(String submissionId, String documentId,
                                       String recipientEmail, String subject) {
        String url = eformsServiceUrl + "/api/eforms/docusign/create-envelope";

        Map<String, String> body = new HashMap<>();
        if (submissionId != null && !submissionId.isBlank()) body.put("submissionId", submissionId);
        if (documentId != null && !documentId.isBlank()) body.put("documentId", documentId);
        body.put("recipientEmail", recipientEmail);
        body.put("subject", subject);

        RestTemplate rest = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        @SuppressWarnings("unchecked")
        ResponseEntity<Map> response = rest.postForEntity(url, new HttpEntity<>(body, headers), Map.class);

        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            Object envId = response.getBody().get("envelopeId");
            if (envId != null) return envId.toString();
        }
        throw new RuntimeException("DocuSign create-envelope call failed: " + response.getStatusCode());
    }

    private static String safeVar(DelegateExecution execution, String name) {
        Object val = execution.getVariable(name);
        return val != null ? val.toString() : "";
    }
}
