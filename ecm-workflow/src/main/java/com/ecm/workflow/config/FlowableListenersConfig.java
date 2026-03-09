package com.ecm.workflow.config;

import com.ecm.workflow.service.WorkflowInstanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.ExecutionListener;
import org.flowable.task.service.delegate.DelegateTask;
import org.flowable.task.service.delegate.TaskListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Flowable delegate expressions resolved from Spring context.
 *
 * In BPMN we reference these as:
 *   flowable:taskListener delegateExpression="${taskCreatedListener}"
 *   flowable:taskListener delegateExpression="${taskCompletedListener}"
 *   flowable:executionListener delegateExpression="${processCompletedListener}"
 *
 * Spring's bean name becomes the expression variable name.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class FlowableListenersConfig {

    private final WorkflowInstanceService workflowInstanceService;
    private final RabbitTemplate rabbitTemplate;

    /**
     * Fires when a user task is created (assigned to candidate group).
     * Publishes a task.assigned event for ecm-notification to send emails/push.
     */
    @Bean
    public TaskListener taskCreatedListener() {
        return (DelegateTask task) -> {
            log.info("Task created: id={}, name={}, candidateGroups={}",
                    task.getId(), task.getName(), task.getVariable("candidateGroup"));

            String documentId    = (String) task.getVariable("documentId");
            String documentName  = (String) task.getVariable("documentName");
            String candidateGroup = (String) task.getVariable("candidateGroup");

            // Publish notification event — best effort, never block workflow
            try {
                Map<String, Object> event = Map.of(
                        "taskId",        task.getId(),
                        "taskName",      task.getName(),
                        "documentId",    documentId != null ? documentId : "",
                        "documentName",  documentName != null ? documentName : "",
                        "assignedGroup", candidateGroup != null ? candidateGroup : "",
                        "processInstanceId", task.getProcessInstanceId()
                );
                rabbitTemplate.convertAndSend(
                        WorkflowRabbitConfig.WORKFLOW_EXCHANGE,
                        WorkflowRabbitConfig.TASK_ASSIGNED_ROUTING_KEY,
                        event);
            } catch (Exception ex) {
                log.warn("Failed to publish task.assigned event for taskId={}: {}",
                        task.getId(), ex.getMessage());
            }
        };
    }

    /**
     * Fires when a user task is completed.
     * Logs the decision for audit purposes.
     */
    @Bean
    public TaskListener taskCompletedListener() {
        return (DelegateTask task) -> {
            String decision   = (String) task.getVariable("decision");
            String reviewedBy = (String) task.getVariable("reviewedBy");
            log.info("Task completed: id={}, decision={}, by={}",
                    task.getId(), decision, reviewedBy);
        };
    }

    /**
     * Fires when the process reaches an end event (approved or rejected).
     * Updates our WorkflowInstanceRecord status via WorkflowInstanceService.
     */
    @Bean
    public ExecutionListener processCompletedListener() {
        return (DelegateExecution execution) -> {
            String endEventName = execution.getCurrentActivityId();
            String decision     = (String) execution.getVariable("decision");
            String comment      = (String) execution.getVariable("comment");
            String instanceRecordId = (String) execution.getVariable("instanceRecordId");

            log.info("Process completed: processInstanceId={}, endEvent={}, decision={}",
                    execution.getProcessInstanceId(), endEventName, decision);

            try {
                workflowInstanceService.markCompleted(
                        execution.getProcessInstanceId(),
                        decision,
                        comment);
            } catch (Exception ex) {
                log.error("Failed to update WorkflowInstanceRecord for processInstanceId={}: {}",
                        execution.getProcessInstanceId(), ex.getMessage());
                // Do NOT rethrow — process must end cleanly
            }

            // Publish completion event.
            // submissionId is stored as a process variable by FormSubmittedListener when
            // a form triggers this workflow. WorkflowCompletedListener in ecm-eforms uses
            // it to update FormSubmission.status and create the document record.
            try {
                Object submissionIdVar = execution.getVariable("submissionId");
                Map<String, Object> event = new java.util.HashMap<>();
                event.put("processInstanceId", execution.getProcessInstanceId());
                event.put("documentId",   String.valueOf(execution.getVariable("documentId")));
                event.put("decision",     decision != null ? decision : "UNKNOWN");
                event.put("comment",      comment  != null ? comment  : "");
                event.put("submissionId", submissionIdVar != null ? submissionIdVar.toString() : null);
                rabbitTemplate.convertAndSend(
                        WorkflowRabbitConfig.WORKFLOW_EXCHANGE,
                        WorkflowRabbitConfig.WORKFLOW_COMPLETED_ROUTING_KEY,
                        event);
            } catch (Exception ex) {
                log.warn("Failed to publish workflow.completed event: {}", ex.getMessage());
            }
        };
    }
}
