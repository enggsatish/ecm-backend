package com.ecm.workflow.listener;

import com.ecm.workflow.model.entity.WorkflowSlaTracking;
import com.ecm.workflow.model.entity.WorkflowTemplate;
import com.ecm.workflow.repository.WorkflowSlaTrackingRepository;
import com.ecm.workflow.service.TemplateResolverService;
import com.ecm.workflow.service.WorkflowInstanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
        try {
            String documentId = (String) event.get("documentId");
            Integer productId  = (Integer) event.get("productId");
            Integer categoryId = (Integer) event.get("categoryId");
            String uploadedBy  = (String) event.get("uploadedBy");

            log.info("Document uploaded event received: documentId={}, product={}, category={}",
                    documentId, productId, categoryId);

            // Resolve the appropriate template
            WorkflowTemplate template = templateResolver.resolve(productId, categoryId);

            // Start workflow instance (existing service method — add templateId param)
            UUID instanceId = workflowInstanceService.startFromTemplate(
                    documentId, template, uploadedBy);

            // Create SLA tracking record
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime deadline = now.plusHours(template.getSlaHours());
            long warningMinutes = (long)(template.getSlaHours() * 60 * template.getWarningThresholdPct() / 100.0);
            LocalDateTime warningAt = now.plusMinutes(warningMinutes);

            LocalDateTime escalationDeadline = template.getEscalationHours() != null
                    ? deadline.plusHours(template.getEscalationHours())
                    : null;

            slaTrackingRepo.save(WorkflowSlaTracking.builder()
                    .workflowInstanceId(instanceId)
                    .template(template)
                    .slaDeadline(deadline)
                    .warningThresholdAt(warningAt)
                    .escalationDeadline(escalationDeadline)
                    .status(WorkflowSlaTracking.Status.ON_TRACK)
                    .build());

            log.info("SLA tracking created for instance: {}, deadline: {}", instanceId, deadline);

        } catch (Exception e) {
            log.error("Failed to process document.uploaded event: {}", e.getMessage(), e);
            throw e; // Re-throw for dead-letter queue
        }
    }
}