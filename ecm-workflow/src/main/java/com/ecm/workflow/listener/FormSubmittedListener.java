package com.ecm.workflow.listener;

import com.ecm.workflow.model.entity.WorkflowDefinitionConfig;
import com.ecm.workflow.repository.WorkflowDefinitionConfigRepository;
import com.ecm.workflow.service.WorkflowInstanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.RuntimeService;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Consumes FORM_SUBMITTED events published by ecm-eforms.
 *
 * Exchange:  ecm.eforms  (declared by ecm-eforms; we bind passively)
 * Queue:     ecm.workflow.form.submitted
 * RK:        form.submitted
 *
 * When a form is submitted it arrives here. If a WorkflowDefinitionConfig
 * with a matching processKey (from the event's workflowDefinitionKey) exists,
 * a Flowable process instance is started and a WorkflowInstanceRecord is persisted.
 *
 * The event payload (from FormEventPublisher.publishSubmitted) contains:
 *   submissionId          — UUID of the FormSubmission
 *   formKey               — form identifier
 *   formVersion           — version string
 *   tenantId              — tenant
 *   submittedBy           — Okta subject (used as startedBySubject)
 *   submittedByName       — display name
 *   workflowDefinitionKey — process key to launch (null if no workflow configured)
 *   defaultPriority       — NORMAL / HIGH / URGENT
 *   timestamp             — event time
 *
 * NOTE: submissionId is stored as a Flowable process variable so that
 * WorkflowCompletedListener in ecm-eforms can retrieve it when the process ends
 * and update the FormSubmission status and create the document record.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FormSubmittedListener {

    private final WorkflowDefinitionConfigRepository definitionConfigRepo;
    private final WorkflowInstanceService            workflowInstanceService;
    private final RuntimeService                     runtimeService;

    @RabbitListener(queues = "ecm.workflow.form.submitted")
    public void onFormSubmitted(Map<String, Object> event) {
        String submissionId = safeStr(event.get("submissionId"));
        String formKey      = safeStr(event.get("formKey"));
        String submittedBy  = safeStr(event.get("submittedBy"));
        String processKey   = safeStr(event.get("workflowDefinitionKey"));

        MDC.put("submissionId", submissionId);
        MDC.put("formKey",      formKey);

        try {
            log.info("FORM_SUBMITTED received: submissionId={}, formKey={}, processKey={}",
                    submissionId, formKey, processKey);

            if (processKey == null || processKey.isBlank()) {
                // Form was submitted but has no workflow attached — nothing to do.
                // The form will stay SUBMITTED until reviewed directly via FormSubmissionService.
                log.info("No workflowDefinitionKey for formKey={} — skipping workflow start", formKey);
                return;
            }

            // Resolve the WorkflowDefinitionConfig by processKey
            WorkflowDefinitionConfig def = definitionConfigRepo
                    .findByProcessKey(processKey)
                    .orElse(null);

            if (def == null) {
                log.warn("No WorkflowDefinitionConfig found for processKey={} — " +
                         "form {} will not get a workflow. " +
                         "Admin must deploy the BPMN and create a definition config.", processKey, formKey);
                return;
            }

            // Start the Flowable process — pass submissionId as a process variable
            // so that processCompletedListener can include it in the workflow.completed event,
            // and WorkflowCompletedListener in ecm-eforms can promote to a document on approval.
            Map<String, Object> extraVars = new HashMap<>();
            extraVars.put("submissionId",  submissionId);
            extraVars.put("formKey",       formKey != null ? formKey : "");
            extraVars.put("submittedBy",   submittedBy != null ? submittedBy : "");

            workflowInstanceService.startFromFormSubmission(
                    submissionId, formKey, def, submittedBy, extraVars);

            log.info("Workflow started for form submission: submissionId={}, processKey={}",
                    submissionId, processKey);

        } catch (Exception e) {
            log.error("Failed to start workflow for formSubmission={}: {}", submissionId, e.getMessage(), e);
            throw e; // re-throw → goes to DLQ
        } finally {
            MDC.clear();
        }
    }

    private static String safeStr(Object o) {
        if (o == null) return null;
        String s = o.toString().trim();
        return s.isBlank() ? null : s;
    }
}
