package com.ecm.workflow.service;

import com.ecm.workflow.model.entity.CategoryWorkflowMapping;
import com.ecm.workflow.model.entity.WorkflowTemplate;
import com.ecm.workflow.repository.CategoryWorkflowMappingRepository;
import com.ecm.workflow.repository.WorkflowTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Resolves which WorkflowTemplate to use for a given document upload.
 *
 * Resolution order:
 *   1. Category mapping — category_workflow_mappings table (specific)
 *   2. Default template — is_default = true AND PUBLISHED (fallback)
 *   3. Empty — no workflow triggered, just OCR
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TemplateResolverService {

    private final WorkflowTemplateRepository templateRepo;
    private final CategoryWorkflowMappingRepository mappingRepo;

    /**
     * Resolves the workflow template for a document upload.
     *
     * @param categoryId the document's category (may be null for uncategorised uploads)
     * @return the resolved template, or empty if no workflow should be triggered
     */
    public Optional<WorkflowTemplate> resolve(Integer categoryId) {

        // 1. Try category-specific mapping
        if (categoryId != null) {
            Optional<CategoryWorkflowMapping> mapping =
                    mappingRepo.findByCategoryIdAndIsActiveTrue(categoryId);

            if (mapping.isPresent()) {
                WorkflowTemplate template = mapping.get().getTemplate();
                if (template.getStatus() == WorkflowTemplate.Status.PUBLISHED) {
                    log.info("Resolved workflow by category mapping: categoryId={} → template='{}' (processKey={})",
                            categoryId, template.getName(), template.getProcessKey());
                    return Optional.of(template);
                } else {
                    log.warn("Category mapping exists for categoryId={} but template '{}' is {} (not PUBLISHED). Skipping.",
                            categoryId, template.getName(), template.getStatus());
                }
            }
        }

        // 2. Fall back to system default template
        Optional<WorkflowTemplate> defaultTemplate =
                templateRepo.findByIsDefaultTrueAndStatus(WorkflowTemplate.Status.PUBLISHED);

        if (defaultTemplate.isPresent()) {
            log.debug("No category mapping for categoryId={}. Using default template '{}'",
                    categoryId, defaultTemplate.get().getName());
            return defaultTemplate;
        }

        // 3. No workflow
        log.info("No workflow template resolved for categoryId={} — no category mapping and no default template. " +
                "Document will proceed with OCR only.", categoryId);
        return Optional.empty();
    }

    /**
     * @deprecated Use {@link #resolve(Integer)} instead.
     */
    @Deprecated
    public Optional<WorkflowTemplate> resolveDefault() {
        return resolve(null);
    }
}
