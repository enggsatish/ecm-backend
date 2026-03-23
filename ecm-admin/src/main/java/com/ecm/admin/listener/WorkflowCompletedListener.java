package com.ecm.admin.listener;

import com.ecm.admin.service.CaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Listens for workflow.completed events from ecm-workflow.
 *
 * When a document workflow completes (APPROVED/REJECTED), updates the
 * corresponding case checklist item status. If all required items are
 * satisfied, the case auto-transitions to REVIEW_PENDING.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowCompletedListener {

    private final CaseService caseService;

    @RabbitListener(queues = "ecm.admin.workflow.completed")
    public void onWorkflowCompleted(Map<String, Object> event) {
        String processInstanceId = str(event.get("processInstanceId"));
        String documentId = str(event.get("documentId"));
        String decision = str(event.get("decision"));

        if (processInstanceId == null) {
            log.warn("Ignoring workflow.completed event — missing processInstanceId: {}", event);
            return;
        }

        log.info("Workflow completed: processInstanceId={}, documentId={}, decision={}",
                processInstanceId, documentId, decision);

        try {
            caseService.onWorkflowCompleted(processInstanceId, documentId, decision);
        } catch (Exception e) {
            log.error("Failed to process workflow completion for processInstanceId={}: {}",
                    processInstanceId, e.getMessage(), e);
            // Don't rethrow — ACK the message. The checklist item can be manually updated.
        }
    }

    private static String str(Object o) {
        return o != null ? o.toString().trim() : null;
    }
}
