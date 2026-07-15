package com.ecm.workflow.listener;

import com.ecm.workflow.config.WorkflowRabbitConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.runtime.Execution;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Listens for DocuSign signing events (form.signed, form.sign.declined) published
 * by ecm-eforms after DocuSign webhook processing.
 *
 * When a DocuSign envelope is completed/declined, this listener finds the Flowable
 * execution waiting at the "docuSignWait" receive task and triggers it to resume
 * the workflow.
 *
 * BPMN pattern:
 *   [DocuSign Service Task] → [Receive Task id="docuSignWait"] → [Manager Review]
 *
 * The receive task waits indefinitely. This listener calls runtimeService.trigger()
 * to resume it with the signing result variables.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocuSignCompletedListener {

    private final RuntimeService runtimeService;

    private static final String RECEIVE_TASK_ACTIVITY_ID = "docuSignWait";

    /**
     * DocuSign envelope completed — signing successful.
     * Resume the waiting receive task with docuSignStatus=completed.
     */
    @RabbitListener(queues = WorkflowRabbitConfig.FORM_SIGNED_QUEUE)
    public void onFormSigned(Map<String, Object> event) {
        String envelopeId = str(event.get("envelopeId"));
        String submissionId = str(event.get("submissionId"));
        String signedDocumentId = str(event.get("signedDocumentId"));

        log.info("[DocuSign Resume] form.signed received: envelopeId={}, submissionId={}",
                envelopeId, submissionId);

        if (envelopeId == null && submissionId == null) {
            log.warn("[DocuSign Resume] No envelopeId or submissionId — cannot find waiting execution");
            return;
        }

        Map<String, Object> variables = new HashMap<>();
        variables.put("docuSignStatus", "completed");
        variables.put("docuSignSignedDocumentId", signedDocumentId);

        triggerWaitingExecution(envelopeId, submissionId, variables);
    }

    /**
     * DocuSign envelope declined — signer refused.
     * Resume the waiting receive task with docuSignStatus=declined.
     */
    @RabbitListener(queues = WorkflowRabbitConfig.FORM_DECLINED_QUEUE)
    public void onFormDeclined(Map<String, Object> event) {
        String envelopeId = str(event.get("envelopeId"));
        String submissionId = str(event.get("submissionId"));
        String reason = str(event.get("declineReason"));

        log.info("[DocuSign Resume] form.sign.declined received: envelopeId={}, reason={}",
                envelopeId, reason);

        Map<String, Object> variables = new HashMap<>();
        variables.put("docuSignStatus", "declined");
        variables.put("docuSignDeclineReason", reason != null ? reason : "No reason provided");

        triggerWaitingExecution(envelopeId, submissionId, variables);
    }

    /**
     * Find the Flowable execution waiting at the receive task and trigger it.
     *
     * Strategy: find executions at the "docuSignWait" activity, then match by
     * process variable docuSignEnvelopeId or submissionId.
     */
    private void triggerWaitingExecution(String envelopeId, String submissionId,
                                          Map<String, Object> variables) {
        try {
            // Find all executions waiting at the docuSignWait receive task
            List<Execution> waitingExecutions = runtimeService.createExecutionQuery()
                    .activityId(RECEIVE_TASK_ACTIVITY_ID)
                    .list();

            if (waitingExecutions.isEmpty()) {
                log.warn("[DocuSign Resume] No executions waiting at '{}' — " +
                         "workflow may not have a receive task or already resumed",
                        RECEIVE_TASK_ACTIVITY_ID);
                return;
            }

            // Match by envelope ID (set by DocuSignDelegate as process variable)
            for (Execution exec : waitingExecutions) {
                String procEnvelopeId = (String) runtimeService
                        .getVariable(exec.getProcessInstanceId(), "docuSignEnvelopeId");
                String procSubmissionId = (String) runtimeService
                        .getVariable(exec.getProcessInstanceId(), "submissionId");

                boolean matches = (envelopeId != null && envelopeId.equals(procEnvelopeId))
                        || (submissionId != null && submissionId.equals(procSubmissionId));

                if (matches) {
                    runtimeService.trigger(exec.getId(), variables);
                    log.info("[DocuSign Resume] Workflow resumed: executionId={}, processInstanceId={}, " +
                             "envelopeId={}, status={}",
                            exec.getId(), exec.getProcessInstanceId(),
                            envelopeId, variables.get("docuSignStatus"));
                    return;
                }
            }

            log.warn("[DocuSign Resume] No matching execution found for envelopeId={}, submissionId={} " +
                     "among {} waiting executions", envelopeId, submissionId, waitingExecutions.size());

        } catch (Exception e) {
            log.error("[DocuSign Resume] Failed to trigger waiting execution: envelopeId={}, error={}",
                    envelopeId, e.getMessage(), e);
        }
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = o.toString().trim();
        return s.isEmpty() ? null : s;
    }
}
