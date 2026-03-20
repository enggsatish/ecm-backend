package com.ecm.workflow.service;

import com.ecm.workflow.model.entity.WorkflowTemplate;
import com.ecm.workflow.repository.WorkflowTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Resolves which WorkflowTemplate to use for a given context.
 *
 * After the v4.0 refactor, workflow routing is driven by:
 *   - product_document_types.on_upload_action (per document type)
 *   - products.case_workflow_key (per case)
 *
 * This service now only provides the system default template as a catch-all
 * for ad-hoc document uploads that don't belong to a case or product.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TemplateResolverService {

    private final WorkflowTemplateRepository templateRepo;

    /**
     * Resolves the system default PUBLISHED template.
     *
     * @return the default template, or empty if none is configured
     */
    public Optional<WorkflowTemplate> resolveDefault() {
        Optional<WorkflowTemplate> defaultTemplate =
                templateRepo.findByIsDefaultTrueAndStatus(WorkflowTemplate.Status.PUBLISHED);

        if (defaultTemplate.isPresent()) {
            log.debug("Using default template '{}'", defaultTemplate.get().getName());
        } else {
            log.warn("No default PUBLISHED template configured. " +
                    "Document will be stored without triggering a workflow. " +
                    "Create a PUBLISHED template and mark it is_default=true in the Workflow Designer.");
        }

        return defaultTemplate;
    }
}
