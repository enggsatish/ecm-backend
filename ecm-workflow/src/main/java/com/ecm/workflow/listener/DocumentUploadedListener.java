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
import java.util.Optional;
import java.util.UUID;

/**
 * Listens on ecm.document → document.uploaded
 * Resolves the correct WorkflowTemplate via TemplateResolverService
 * and starts a process instance via WorkflowInstanceService.
 *
 * Failure strategy:
 * ─────────────────
 * Messaging errors (malformed payload, DB down, Flowable error) → re-throw → DLQ.
 * Configuration gaps (no template, no definition config)        → log + ACK cleanly.
 *
 * A configuration gap is not a messaging error. Dead-lettering the message
 * would cause repeated failed retries on every restart and pollute the DLQ
 * with noise that hides genuine failures. The correct remediation for a
 * config gap is admin action in the Workflow Designer, not message replay.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentUploadedListener {

    private final TemplateResolverService      templateResolver;
    private final WorkflowInstanceService      workflowInstanceService;
    private final WorkflowSlaTrackingRepository slaTrackingRepo;

    @RabbitListener(queues = "${ecm.rabbitmq.queues.document-uploaded}")
    public void onDocumentUploaded(Map<String, Object> event) {

        String  documentId      = (String)  event.get("documentId");
        String  partyExternalId = (String)  event.get("partyExternalId");
        Integer categoryId      = event.get("categoryId") instanceof Integer i ? i : null;
        String  uploadedBy      = (String)  event.get("uploadedBy");

        MDC.put("documentId", documentId);
        try {
            log.info("document.workflow.trigger: documentId={}, party={}, category={}",
                    documentId, partyExternalId, categoryId);

            // ── 1. Resolve template ──────────────────────────────────────────
            Optional<WorkflowTemplate> templateOpt;
            if (partyExternalId == null || partyExternalId.isBlank()) {
                log.info("No partyExternalId for documentId={} — routing to unlinked triage",
                        documentId);
                templateOpt = templateResolver.resolveUnlinked();
            } else {
                templateOpt = templateResolver.resolve(null, categoryId);
            }

            if (templateOpt.isEmpty()) {
                // No template configured yet — normal on a fresh install.
                // ACK the message so it doesn't pollute the DLQ.
                log.warn("Skipping workflow for documentId={} — no PUBLISHED template is " +
                                "configured. Create a template in the Workflow Designer and publish it.",
                        documentId);
                return;
            }

            WorkflowTemplate template = templateOpt.get();

            // ── 2. Start workflow instance ───────────────────────────────────
            UUID instanceId;
            try {
                instanceId = workflowInstanceService.startFromTemplate(
                        documentId, template, uploadedBy);
            } catch (IllegalStateException configGap) {
                // startFromTemplate throws when no WorkflowDefinitionConfig exists for the
                // processKey. This is a config gap (publish didn't sync the config row),
                // not a message error. ACK cleanly and let the admin fix it.
                log.warn("Workflow config gap for documentId={}, template='{}', processKey='{}': {}. " +
                                "Re-publish the template in the Workflow Designer to auto-create the config.",
                        documentId, template.getName(), template.getProcessKey(),
                        configGap.getMessage());
                return;
            }

            if (instanceId == null) {
                log.warn("startFromTemplate returned null for documentId={} — workflow not started",
                        documentId);
                return;
            }

            // ── 3. SLA tracking ──────────────────────────────────────────────
            LocalDateTime now            = LocalDateTime.now();
            LocalDateTime deadline       = now.plusHours(template.getSlaHours());
            long          warningMinutes = (long)(template.getSlaHours() * 60
                    * template.getWarningThresholdPct() / 100.0);

            slaTrackingRepo.save(WorkflowSlaTracking.builder()
                    .workflowInstanceId(instanceId)
                    .template(template)
                    .slaDeadline(deadline)
                    .warningThresholdAt(now.plusMinutes(warningMinutes))
                    .escalationDeadline(template.getEscalationHours() != null
                            ? deadline.plusHours(template.getEscalationHours()) : null)
                    .status(WorkflowSlaTracking.Status.ON_TRACK)
                    .build());

            log.info("Workflow started: instanceId={}, template='{}', deadline={}",
                    instanceId, template.getName(), deadline);

        } catch (Exception e) {
            // Genuine messaging / infrastructure error → re-throw to DLQ
            log.error("Failed document.workflow.trigger for documentId={}: {}",
                    documentId, e.getMessage(), e);
            throw e;
        } finally {
            MDC.clear();
        }
    }
}