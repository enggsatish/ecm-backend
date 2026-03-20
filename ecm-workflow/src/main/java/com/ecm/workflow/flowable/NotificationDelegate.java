package com.ecm.workflow.flowable;

import com.ecm.workflow.config.WorkflowRabbitConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Flowable service-task delegate for NOTIFICATION steps.
 *
 * Referenced in BPMN via: flowable:delegateExpression="${notificationDelegate}"
 *
 * Publishes a notification event to the ecm.notifications exchange (fire-and-forget).
 * A future ecm-notification service will consume these events and send emails/push/SMS.
 * Until that service exists, messages are published but silently dropped by RabbitMQ
 * (no consumer bound to the exchange).
 *
 * This delegate NEVER throws — notification failure must not block the workflow.
 */
@Slf4j
@Component("notificationDelegate")
@RequiredArgsConstructor
public class NotificationDelegate implements JavaDelegate {

    private final RabbitTemplate rabbitTemplate;

    @Override
    public void execute(DelegateExecution execution) {
        try {
            Map<String, Object> event = new HashMap<>();
            event.put("eventType",          "WORKFLOW_NOTIFICATION");
            event.put("processInstanceId",  execution.getProcessInstanceId());
            event.put("activityId",         execution.getCurrentActivityId());
            event.put("activityName",       execution.getCurrentFlowElement() != null
                                                ? execution.getCurrentFlowElement().getName() : "");
            event.put("submissionId",       safeVar(execution, "submissionId"));
            event.put("formKey",            safeVar(execution, "formKey"));
            event.put("submittedBy",        safeVar(execution, "submittedBy"));
            event.put("startedBy",          safeVar(execution, "startedBy"));
            event.put("candidateGroup",     safeVar(execution, "candidateGroup"));
            event.put("documentId",         safeVar(execution, "documentId"));
            event.put("timestamp",          OffsetDateTime.now());

            rabbitTemplate.convertAndSend(
                    WorkflowRabbitConfig.NOTIFICATIONS_EXCHANGE,
                    "notification.email",
                    event);

            log.info("Notification event published: activity={}, processInstance={}",
                    execution.getCurrentActivityId(), execution.getProcessInstanceId());

        } catch (Exception e) {
            // Fire-and-forget: log and continue — never block the workflow
            log.warn("Failed to publish notification event for processInstance={}: {}",
                    execution.getProcessInstanceId(), e.getMessage());
        }
    }

    private static String safeVar(DelegateExecution execution, String name) {
        Object val = execution.getVariable(name);
        return val != null ? val.toString() : "";
    }
}
