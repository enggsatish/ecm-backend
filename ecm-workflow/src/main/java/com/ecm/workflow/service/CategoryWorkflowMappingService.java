package com.ecm.workflow.service;

import com.ecm.workflow.model.entity.CategoryWorkflowMapping;
import com.ecm.workflow.model.entity.WorkflowTemplate;
import com.ecm.workflow.repository.CategoryWorkflowMappingRepository;
import com.ecm.workflow.repository.WorkflowTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CategoryWorkflowMappingService {

    private final CategoryWorkflowMappingRepository mappingRepo;
    private final WorkflowTemplateRepository templateRepo;

    @Transactional(readOnly = true)
    public List<CategoryWorkflowMapping> listAll() {
        return mappingRepo.findAll();
    }

    @Transactional
    public CategoryWorkflowMapping create(Integer categoryId, Integer templateId, String createdBy) {
        // Validate template exists and is PUBLISHED
        WorkflowTemplate template = templateRepo.findById(templateId)
                .orElseThrow(() -> new IllegalArgumentException("Template not found: " + templateId));

        if (template.getStatus() != WorkflowTemplate.Status.PUBLISHED) {
            throw new IllegalStateException(
                    "Template '" + template.getName() + "' is " + template.getStatus() +
                    ". Only PUBLISHED templates can be mapped to categories.");
        }

        // Check for existing mapping — update if exists, create if not
        var existing = mappingRepo.findByCategoryIdAndIsActiveTrue(categoryId);
        if (existing.isPresent()) {
            // Update the existing mapping to point to the new template
            CategoryWorkflowMapping mapping = existing.get();
            mapping.setTemplate(template);
            mapping.setCreatedBy(createdBy);
            log.info("Updated category mapping: categoryId={} → templateId={} (was {})",
                    categoryId, templateId, existing.get().getTemplate().getId());
            return mappingRepo.save(mapping);
        }

        CategoryWorkflowMapping mapping = CategoryWorkflowMapping.builder()
                .categoryId(categoryId)
                .template(template)
                .createdBy(createdBy)
                .build();

        CategoryWorkflowMapping saved = mappingRepo.save(mapping);
        log.info("Created category mapping: categoryId={} → template='{}' (id={})",
                categoryId, template.getName(), templateId);
        return saved;
    }

    @Transactional
    public void delete(Integer id) {
        CategoryWorkflowMapping mapping = mappingRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Mapping not found: " + id));
        mappingRepo.delete(mapping);
        log.info("Deleted category mapping id={}, categoryId={}", id, mapping.getCategoryId());
    }
}
