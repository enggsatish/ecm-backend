package com.ecm.workflow.config;

import com.ecm.workflow.service.WorkflowTemplateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Auto-deploys workflow templates to Flowable on application startup.
 *
 * Fixes the bootstrap gap where init.sql seeds templates with status=PUBLISHED
 * but never calls the Flowable deployment API. Without this, form submissions
 * that trigger workflows will fail with "no process definition found" and
 * endlessly retry in RabbitMQ.
 *
 * Only deploys templates where:
 *   - status = PUBLISHED
 *   - flowable_deployment_id IS NULL (not yet deployed to Flowable)
 *   - bpmn_xml IS NOT NULL (has deployable BPMN)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowAutoDeployConfig {

    private final WorkflowTemplateService templateService;

    @EventListener(ApplicationReadyEvent.class)
    public void deployPendingTemplates() {
        try {
            int deployed = templateService.autoDeployPendingTemplates();
            if (deployed > 0) {
                log.info("Auto-deployed {} workflow template(s) to Flowable on startup", deployed);
            } else {
                log.debug("No pending workflow templates to auto-deploy");
            }
        } catch (Exception e) {
            log.error("Failed to auto-deploy workflow templates on startup: {}", e.getMessage(), e);
            // Don't prevent app startup — templates can be deployed manually via UI
        }
    }
}
