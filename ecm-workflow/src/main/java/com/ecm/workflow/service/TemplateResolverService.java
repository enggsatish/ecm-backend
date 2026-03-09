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
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TemplateResolverService {

    private final WorkflowTemplateMappingRepository mappingRepo;
    private final WorkflowTemplateRepository templateRepo;

    public WorkflowTemplate resolve(Integer productId, Integer categoryId) {
        // Step 1 + 2: query returns candidates ordered by specificity
        List<WorkflowTemplateMapping> candidates = mappingRepo.findCandidates(productId, categoryId);

        if (!candidates.isEmpty()) {
            WorkflowTemplate resolved = candidates.get(0).getTemplate();
            log.debug("Resolved template '{}' for product={}, category={}",
                    resolved.getName(), productId, categoryId);
            return resolved;
        }

        // Step 3: system default catch-all
        Optional<WorkflowTemplate> defaultTemplate =
                templateRepo.findByIsDefaultTrueAndStatus(WorkflowTemplate.Status.PUBLISHED);

        if (defaultTemplate.isPresent()) {
            log.debug("Using default template '{}' for product={}, category={}",
                    defaultTemplate.get().getName(), productId, categoryId);
            return defaultTemplate.get();
        }

        throw new IllegalStateException(
                "No workflow template found for product=" + productId +
                        ", category=" + categoryId +
                        " and no default template is configured.");
    }

    public WorkflowTemplate resolveUnlinked() {
        return templateRepo.findByProcessKeyAndStatus(
                        "unlinked-document-triage", WorkflowTemplate.Status.PUBLISHED)
                .orElseGet(() -> {
                    log.warn("No 'unlinked-document-triage' template found — using system default");
                    return templateRepo.findByIsDefaultTrueAndStatus(WorkflowTemplate.Status.PUBLISHED)
                            .orElseThrow(() -> new IllegalStateException(
                                    "No unlinked-document-triage template and no system default. " +
                                            "Create a template with processKey='unlinked-document-triage'."));
                });
    }
}