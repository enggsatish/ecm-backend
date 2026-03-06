package com.ecm.workflow.job;

import com.ecm.workflow.model.entity.WorkflowSlaTracking;
import com.ecm.workflow.repository.WorkflowSlaTrackingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Runs every 5 minutes.
 * Scans workflow_sla_tracking for records that have crossed a threshold
 * and publishes events to ecm.workflow exchange for ecm-notification to consume.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlaBreachDetectionJob {

    private static final String EXCHANGE = "ecm.workflow";

    private final WorkflowSlaTrackingRepository slaRepo;
    private final RabbitTemplate rabbitTemplate;
    private final TaskService flowableTaskService;

    @Scheduled(fixedDelayString = "${ecm.sla.check-interval-ms:300000}") // default 5 min
    @Transactional
    public void run() {
        LocalDateTime now = LocalDateTime.now();
        log.debug("SLA breach detection job running at {}", now);

        processWarnings(now);
        processBreaches(now);
        processEscalations(now);
    }

    private void processWarnings(LocalDateTime now) {
        List<WorkflowSlaTracking> dueForWarning = slaRepo.findDueForWarning(now);
        for (WorkflowSlaTracking tracking : dueForWarning) {
            tracking.setStatus(WorkflowSlaTracking.Status.WARNING);
            tracking.setWarningSentAt(now);
            slaRepo.save(tracking);

            publishEvent("workflow.sla.warning", Map.of(
                    "workflowInstanceId", tracking.getWorkflowInstanceId().toString(),
                    "slaDeadline", tracking.getSlaDeadline().toString(),
                    "templateId", tracking.getTemplate() != null
                            ? tracking.getTemplate().getId() : null
            ));
            log.info("SLA WARNING published for instance: {}", tracking.getWorkflowInstanceId());
        }
    }

    private void processBreaches(LocalDateTime now) {
        List<WorkflowSlaTracking> breached = slaRepo.findBreached(now);
        for (WorkflowSlaTracking tracking : breached) {
            tracking.setStatus(WorkflowSlaTracking.Status.BREACHED);
            tracking.setBreachedAt(now);
            slaRepo.save(tracking);

            publishEvent("workflow.sla.breached", Map.of(
                    "workflowInstanceId", tracking.getWorkflowInstanceId().toString(),
                    "breachedAt", now.toString()
            ));
            log.warn("SLA BREACHED for instance: {}", tracking.getWorkflowInstanceId());
        }
    }

    private void processEscalations(LocalDateTime now) {
        List<WorkflowSlaTracking> dueForEscalation = slaRepo.findDueForEscalation(now);
        for (WorkflowSlaTracking tracking : dueForEscalation) {

            // Reassign the active Flowable task to the escalation group
            String escalationGroup = tracking.getTemplate() != null
                    ? tracking.getTemplate().getEscalationGroupKey()
                    : null;

            if (escalationGroup != null) {
                Task task = flowableTaskService.createTaskQuery()
                        .processInstanceId(tracking.getWorkflowInstanceId().toString())
                        .singleResult();
                if (task != null) {
                    flowableTaskService.deleteCandidateGroup(task.getId(), escalationGroup);
                    flowableTaskService.addCandidateGroup(task.getId(), escalationGroup);
                    log.info("Escalated task {} to group: {}", task.getId(), escalationGroup);
                }
            }

            tracking.setStatus(WorkflowSlaTracking.Status.ESCALATED);
            tracking.setEscalatedAt(now);
            slaRepo.save(tracking);

            publishEvent("workflow.sla.escalated", Map.of(
                    "workflowInstanceId", tracking.getWorkflowInstanceId().toString(),
                    "escalationGroup", escalationGroup != null ? escalationGroup : "unknown",
                    "escalatedAt", now.toString()
            ));
        }
    }

    private void publishEvent(String routingKey, Map<String, Object> payload) {
        try {
            rabbitTemplate.convertAndSend(EXCHANGE, routingKey, payload);
        } catch (Exception e) {
            log.error("Failed to publish SLA event '{}': {}", routingKey, e.getMessage());
        }
    }
}