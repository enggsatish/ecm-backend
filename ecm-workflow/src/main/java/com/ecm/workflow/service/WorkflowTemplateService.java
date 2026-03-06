package com.ecm.workflow.service;

import com.ecm.workflow.model.dsl.WorkflowTemplateDsl;
import com.ecm.workflow.model.entity.WorkflowTemplate;
import com.ecm.workflow.model.entity.WorkflowTemplateMapping;
import com.ecm.workflow.repository.WorkflowTemplateMappingRepository;
import com.ecm.workflow.repository.WorkflowTemplateRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowTemplateService {

    private final WorkflowTemplateRepository templateRepo;
    private final WorkflowTemplateMappingRepository mappingRepo;
    private final BpmnGeneratorService bpmnGenerator;
    private final FlowableDeploymentService deploymentService;
    private final ObjectMapper objectMapper;

    // ─── CRUD ────────────────────────────────────────────────────────────────

    public List<WorkflowTemplate> listAll() {
        return templateRepo.findAll();
    }

    public WorkflowTemplate getById(Integer id) {
        return templateRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Template not found: " + id));
    }

    @Transactional
    public WorkflowTemplate create(WorkflowTemplateDsl dsl, Integer slaHours,
                                   Integer warningPct, Integer escalationHours,
                                   String escalationGroupKey, String createdBy) {
        try {
            String dslJson = objectMapper.writeValueAsString(dsl);
            WorkflowTemplate template = WorkflowTemplate.builder()
                    .name(dsl.getName())
                    .dslDefinition(dslJson)
                    .status(WorkflowTemplate.Status.DRAFT)
                    .slaHours(slaHours != null ? slaHours : 48)
                    .warningThresholdPct(warningPct != null ? warningPct : 80)
                    .escalationHours(escalationHours)
                    .escalationGroupKey(escalationGroupKey)
                    .createdBy(createdBy)
                    .build();
            return templateRepo.save(template);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create template", e);
        }
    }

    @Transactional
    public WorkflowTemplate updateDsl(Integer id, WorkflowTemplateDsl dsl) {
        WorkflowTemplate template = getById(id);
        if (template.getStatus() != WorkflowTemplate.Status.DRAFT) {
            throw new IllegalStateException("Only DRAFT templates can be edited. Deprecate and create a new version.");
        }
        try {
            template.setDslDefinition(objectMapper.writeValueAsString(dsl));
            template.setName(dsl.getName());
            return templateRepo.save(template);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to update DSL", e);
        }
    }

    /**
     * Publish: generate BPMN XML from DSL, deploy to Flowable, mark PUBLISHED.
     */
    @Transactional
    public WorkflowTemplate publish(Integer id) {
        WorkflowTemplate template = getById(id);
        if (template.getStatus() == WorkflowTemplate.Status.PUBLISHED) {
            throw new IllegalStateException("Template is already published.");
        }

        WorkflowTemplateDsl dsl = template.getDsl(objectMapper);

        // Validate DSL has at least one step
        if (dsl.getSteps() == null || dsl.getSteps().isEmpty()) {
            throw new IllegalStateException("Cannot publish a template with no steps defined.");
        }

        // Generate + deploy
        String bpmnXml = bpmnGenerator.generate(dsl);
        FlowableDeploymentService.DeploymentResult result =
                deploymentService.deploy(bpmnXml, template.getName());

        template.setProcessKey(result.processDefinitionKey());
        template.setFlowableDeploymentId(result.deploymentId());
        template.setFlowableProcessDefId(result.processDefinitionId());
        template.setStatus(WorkflowTemplate.Status.PUBLISHED);
        template.setVersion(result.version());

        log.info("Template '{}' published as process key: {}", template.getName(), result.processDefinitionKey());
        return templateRepo.save(template);
    }

    /**
     * Deprecate — prevents new instances but does not affect running ones.
     */
    @Transactional
    public WorkflowTemplate deprecate(Integer id) {
        WorkflowTemplate template = getById(id);
        template.setStatus(WorkflowTemplate.Status.DEPRECATED);
        return templateRepo.save(template);
    }

    // ─── Preview ─────────────────────────────────────────────────────────────

    /**
     * Generate and return BPMN XML without deploying — for preview/validation.
     */
    public String previewBpmn(Integer id) {
        WorkflowTemplate template = getById(id);
        WorkflowTemplateDsl dsl = template.getDsl(objectMapper);
        return bpmnGenerator.generate(dsl);
    }

    // ─── Mappings ─────────────────────────────────────────────────────────────

    @Transactional
    public WorkflowTemplateMapping addMapping(Integer templateId, Integer productId,
                                              Integer categoryId, Integer priority) {
        WorkflowTemplate template = getById(templateId);
        WorkflowTemplateMapping mapping = WorkflowTemplateMapping.builder()
                .template(template)
                .productId(productId)
                .categoryId(categoryId)
                .priority(priority != null ? priority : 100)
                .build();
        return mappingRepo.save(mapping);
    }

    @Transactional
    public void removeMapping(Integer mappingId) {
        mappingRepo.deleteById(mappingId);
    }

    public List<WorkflowTemplateMapping> getMappings(Integer templateId) {
        return mappingRepo.findByTemplateId(templateId);
    }
}