package com.ecm.workflow.service;

import com.ecm.workflow.model.entity.WorkflowTemplate;
import com.ecm.workflow.model.entity.WorkflowTemplateMapping;
import com.ecm.workflow.repository.WorkflowTemplateMappingRepository;
import com.ecm.workflow.repository.WorkflowTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Resolves which WorkflowTemplate to use for a given product+category combination.
 *
 * Resolution order (first match wins):
 *   1. product_id + category_id  (exact match, lowest priority value)
 *   2. NULL product_id + category_id  (category-level default)
 *   3. is_default = true template  (system catch-all)
 *
 * Both methods return Optional — callers decide whether to skip, warn, or error.
 * This avoids dead-lettering RabbitMQ messages when no template is configured yet,
 * which is the normal state on a clean install before an admin creates any templates.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TemplateResolverService {

    private final WorkflowTemplateMappingRepository mappingRepo;
    private final WorkflowTemplateRepository        templateRepo;

    /**
     * Resolves the best-matching PUBLISHED template for a document upload.
     *
     * @param productId  optional; pass null when the document has no product link
     * @param categoryId optional; pass null when the document has no category
     * @return the resolved template, or empty if no template is configured
     */
    public Optional<WorkflowTemplate> resolve(Integer productId, Integer categoryId) {

        // Step 1 + 2: mapping table — most-specific match first
        List<WorkflowTemplateMapping> candidates =
                mappingRepo.findCandidates(productId, categoryId);

        if (!candidates.isEmpty()) {
            WorkflowTemplate resolved = candidates.get(0).getTemplate();
            log.debug("Resolved template '{}' via mapping for product={}, category={}",
                    resolved.getName(), productId, categoryId);
            return Optional.of(resolved);
        }

        // Step 3: system catch-all (is_default = true)
        Optional<WorkflowTemplate> defaultTemplate =
                templateRepo.findByIsDefaultTrueAndStatus(WorkflowTemplate.Status.PUBLISHED);

        if (defaultTemplate.isPresent()) {
            log.debug("Using default catch-all template '{}' for product={}, category={}",
                    defaultTemplate.get().getName(), productId, categoryId);
        } else {
            log.warn("No workflow template found for product={}, category={} and no default " +
                            "template is configured. Upload will be stored without triggering a workflow. " +
                            "Create a PUBLISHED template and mark it is_default=true in the Workflow Designer.",
                    productId, categoryId);
        }

        return defaultTemplate;
    }

    /**
     * Resolves a template for documents uploaded without a partyExternalId.
     *
     * Previously this method looked up a hardcoded 'unlinked-document-triage' processKey.
     * That key was seeded by V4/V5 migrations which have been removed.
     *
     * The current strategy falls back directly to the system default template.
     * If you want a dedicated triage workflow for unlinked documents, create a PUBLISHED
     * template in the designer and mark it is_default=true — or create a specific mapping.
     *
     * @return the system default template, or empty if none is configured
     */
    public Optional<WorkflowTemplate> resolveUnlinked() {
        Optional<WorkflowTemplate> defaultTemplate =
                templateRepo.findByIsDefaultTrueAndStatus(WorkflowTemplate.Status.PUBLISHED);

        if (defaultTemplate.isPresent()) {
            log.debug("Routing unlinked document to default template '{}'",
                    defaultTemplate.get().getName());
        } else {
            log.warn("No default PUBLISHED template configured for unlinked document triage. " +
                    "Document will be stored without triggering a workflow. " +
                    "Create a PUBLISHED template and mark it is_default=true in the Workflow Designer.");
        }

        return defaultTemplate;
    }
}