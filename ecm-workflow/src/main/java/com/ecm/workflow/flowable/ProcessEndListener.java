package com.ecm.workflow.flowable;

import com.ecm.workflow.service.WorkflowInstanceService;
import lombok.extern.slf4j.Slf4j;
import org.flowable.common.engine.api.delegate.Expression;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.ExecutionListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Flowable execution listener that fires when a process reaches an end event.
 *
 * Referenced in BPMN via:
 *   flowable:class="com.ecm.workflow.flowable.ProcessEndListener"
 *
 * Performs two actions:
 *   1. Updates the WorkflowInstanceRecord status to COMPLETED_APPROVED / COMPLETED_REJECTED
 *   2. Publishes a workflow.completed event to RabbitMQ so ecm-eforms can
 *      update FormSubmission status and create the document
 */
@Slf4j
@Component
public class ProcessEndListener implements ExecutionListener {

    private Expression completionStatus;

    private static WorkflowInstanceService workflowInstanceService;
    private static RabbitTemplate rabbitTemplate;

    @Autowired
    public void setWorkflowInstanceService(WorkflowInstanceService wis) {
        ProcessEndListener.workflowInstanceService = wis;
    }

    @Autowired
    public void setRabbitTemplate(RabbitTemplate rt) {
        ProcessEndListener.rabbitTemplate = rt;
    }

    @Override
    public void notify(DelegateExecution execution) {
        String decision = (String) execution.getVariable("decision");
        String comment  = (String) execution.getVariable("comment");
        String status   = completionStatus != null
                ? (String) completionStatus.getValue(execution)
                : "COMPLETED";

        log.info("Process ended: processInstanceId={}, endEvent={}, decision={}, status={}",
                execution.getProcessInstanceId(),
                execution.getCurrentActivityId(),
                decision, status);

        // 1. Update WorkflowInstanceRecord
        try {
            workflowInstanceService.markCompleted(
                    execution.getProcessInstanceId(),
                    decision,
                    comment);
        } catch (Exception ex) {
            log.error("Failed to update WorkflowInstanceRecord for processInstanceId={}: {}",
                    execution.getProcessInstanceId(), ex.getMessage());
        }

        // 2. Publish workflow.completed event
        try {
            Object submissionIdVar = execution.getVariable("submissionId");
            Map<String, Object> event = new HashMap<>();
            event.put("processInstanceId", execution.getProcessInstanceId());
            event.put("documentId",   String.valueOf(execution.getVariable("documentId")));
            event.put("decision",     decision != null ? decision : "UNKNOWN");
            event.put("comment",      comment  != null ? comment  : "");
            event.put("submissionId", submissionIdVar != null ? submissionIdVar.toString() : null);

            rabbitTemplate.convertAndSend(
                    "ecm.workflow",
                    "workflow.completed",
                    event);
            log.info("Published workflow.completed: processInstanceId={}, decision={}, submissionId={}",
                    execution.getProcessInstanceId(), decision, submissionIdVar);
        } catch (Exception ex) {
            log.warn("Failed to publish workflow.completed event: {}", ex.getMessage());
        }
    }
}
