package com.ecm.workflow.listener;

import com.ecm.workflow.service.WorkflowInstanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Listens for case.workflow.cancel events from ecm-admin.
 * When a case is cancelled/rejected, ecm-admin publishes cancel events
 * for each active workflow. This listener stops the Flowable process.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CaseWorkflowCancelListener {

    private final WorkflowInstanceService workflowInstanceService;

    @RabbitListener(queues = "ecm.workflow.case.cancel")
    public void onCaseWorkflowCancel(Map<String, Object> event) {
        String caseId = str(event.get("caseId"));
        String workflowInstanceId = str(event.get("workflowInstanceId"));

        if (workflowInstanceId == null) {
            log.warn("Ignoring case.workflow.cancel — missing workflowInstanceId: {}", event);
            return;
        }

        log.info("Cancelling workflow for closed case: caseId={}, workflowInstanceId={}",
                caseId, workflowInstanceId);

        try {
            // Find the workflow instance record by processInstanceId and cancel it
            workflowInstanceService.cancelByProcessInstanceId(workflowInstanceId, "Case closed");
        } catch (Exception e) {
            log.warn("Failed to cancel workflow {} for case {}: {}",
                    workflowInstanceId, caseId, e.getMessage());
            // Don't rethrow — the checklist item is already marked TERMINATED in ecm-admin
        }
    }

    private static String str(Object o) {
        return o != null ? o.toString().trim() : null;
    }
}
