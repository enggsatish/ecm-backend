package com.ecm.workflow.listener;

import com.ecm.workflow.model.entity.WorkflowSlaTracking;
import com.ecm.workflow.model.entity.WorkflowTemplate;
import com.ecm.workflow.repository.WorkflowSlaTrackingRepository;
import com.ecm.workflow.service.TemplateResolverService;
import com.ecm.workflow.service.WorkflowInstanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Listens on ecm.document → document.uploaded
 * Resolves the correct WorkflowTemplate via TemplateResolverService
 * and starts a process instance via WorkflowInstanceService.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentUploadedListener {

    private final TemplateResolverService templateResolver;
    private final WorkflowInstanceService workflowInstanceService;
    private final WorkflowSlaTrackingRepository slaTrackingRepo;

    @RabbitListener(queues = "${ecm.rabbitmq.queues.document-uploaded}")
    public void onDocumentUploaded(Map<String, Object> event) {
        String documentId      = (String) event.get("documentId");
        String partyExternalId = (String) event.get("partyExternalId");
        Integer categoryId     = event.get("categoryId") instanceof Integer i ? i : null;
        String uploadedBy      = (String) event.get("uploadedBy");

        MDC.put("documentId", documentId);
        try {
            log.info("document.workflow.trigger: documentId={}, party={}, category={}",
                    documentId, partyExternalId, categoryId);

            WorkflowTemplate template;

            try {
                if (partyExternalId == null || partyExternalId.isBlank()) {
                    log.info("No partyExternalId for documentId={} — routing to unlinked triage", documentId);
                    template = templateResolver.resolveUnlinked();
                } else {
                    template = templateResolver.resolve(null, categoryId);
                }
            } catch (IllegalStateException noTemplate) {
                // No template configured — this is a setup/seed-data gap, not a messaging error.
                // Log clearly for the admin, ACK the message, and return.
                // Re-throwing would DLQ the message and cause repeated failed retries
                // every time the service restarts until seed data is applied.
                log.warn("No workflow template found for documentId={} — " +
                                "workflow will not be started. " +
                                "Apply V4__seed_workflow_templates.sql migration or create a " +
                                "PUBLISHED template with processKey='unlinked-document-triage' " +
                                "and set is_default=true on at least one template. Error: {}",
                        documentId, noTemplate.getMessage());
                return;  // ACK the message cleanly
            }

            UUID instanceId = workflowInstanceService.startFromTemplate(
                    documentId, template, uploadedBy);

            // SLA tracking — existing code unchanged
            LocalDateTime now      = LocalDateTime.now();
            LocalDateTime deadline = now.plusHours(template.getSlaHours());
            long warningMinutes    = (long)(template.getSlaHours() * 60 * template.getWarningThresholdPct() / 100.0);

            slaTrackingRepo.save(WorkflowSlaTracking.builder()
                    .workflowInstanceId(instanceId)
                    .template(template)
                    .slaDeadline(deadline)
                    .warningThresholdAt(now.plusMinutes(warningMinutes))
                    .escalationDeadline(template.getEscalationHours() != null
                            ? deadline.plusHours(template.getEscalationHours()) : null)
                    .status(WorkflowSlaTracking.Status.ON_TRACK)
                    .build());

            log.info("Workflow started: instanceId={}, deadline={}", instanceId, deadline);

        } catch (Exception e) {
            log.error("Failed document.workflow.trigger for documentId={}: {}", documentId, e.getMessage(), e);
            throw e; // → DLQ
        } finally {
            MDC.clear();
        }
    }
}