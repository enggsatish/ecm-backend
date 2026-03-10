package com.ecm.workflow.service;

import com.ecm.workflow.model.dsl.WorkflowTemplateDsl;
import com.ecm.workflow.model.entity.WorkflowDefinitionConfig;
import com.ecm.workflow.model.entity.WorkflowTemplate;
import com.ecm.workflow.model.entity.WorkflowTemplate.BpmnSource;
import com.ecm.workflow.model.entity.WorkflowTemplateMapping;
import com.ecm.workflow.repository.WorkflowDefinitionConfigRepository;
import com.ecm.workflow.repository.WorkflowTemplateMappingRepository;
import com.ecm.workflow.repository.WorkflowTemplateRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowTemplateService {

    private final WorkflowTemplateRepository templateRepo;
    private final WorkflowTemplateMappingRepository mappingRepo;
    private final BpmnGeneratorService bpmnGenerator;
    private final FlowableDeploymentService deploymentService;
    private final ObjectMapper objectMapper;
    // Injected to sync WorkflowDefinitionConfig on publish — fixes the
    // "No WorkflowDefinitionConfig found for processKey" IllegalStateException
    // that occurs when startFromTemplate() is called by the document upload listener.
    private final WorkflowDefinitionConfigRepository definitionConfigRepo;

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
                    .bpmnSource(BpmnSource.DSL)
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

    // ─── DSL update (legacy / simple step builder) ───────────────────────────

    @Transactional
    public WorkflowTemplate updateDsl(Integer id, WorkflowTemplateDsl dsl) {
        WorkflowTemplate template = getDraftOrThrow(id);
        try {
            template.setDslDefinition(objectMapper.writeValueAsString(dsl));
            template.setName(dsl.getName());
            template.setBpmnSource(BpmnSource.DSL);   // revert to DSL-generated path
            template.setBpmnXml(null);                 // clear any stored BPMN XML
            return templateRepo.save(template);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to update DSL", e);
        }
    }

    // ─── BPMN XML update (visual designer) ───────────────────────────────────

    /**
     * Persist raw BPMN 2.0 XML authored in the bpmn.io visual designer.
     * Switches the template's authoring mode to VISUAL so that publish
     * deploys this XML directly to Flowable rather than generating from DSL.
     *
     * @param id      template id (must be DRAFT)
     * @param bpmnXml well-formed BPMN 2.0 XML string
     * @return updated template
     */
    @Transactional
    public WorkflowTemplate updateBpmnXml(Integer id, String bpmnXml) {
        if (bpmnXml == null || bpmnXml.isBlank()) {
            throw new IllegalArgumentException("bpmnXml must not be blank");
        }
        if (!bpmnXml.contains("</definitions>") && !bpmnXml.contains("<definitions")) {
            throw new IllegalArgumentException("Payload does not look like valid BPMN XML");
        }

        WorkflowTemplate template = getDraftOrThrow(id);
        template.setBpmnXml(bpmnXml);
        template.setBpmnSource(BpmnSource.VISUAL);

        // Attempt to extract the process name from the XML for display;
        // fail gracefully — the name set at creation is fine too.
        extractProcessName(bpmnXml).ifPresent(template::setName);

        log.info("Stored visual BPMN XML for template id={} ({})", id, template.getName());
        return templateRepo.save(template);
    }

    // ─── Preview ─────────────────────────────────────────────────────────────

    /**
     * Returns the BPMN XML that would be deployed if this template were published
     * right now — without actually deploying.
     *
     * For VISUAL-source templates the stored bpmn_xml is returned as-is.
     * For DSL-source templates the BPMN is freshly generated from the DSL.
     */
    public String previewBpmn(Integer id) {
        WorkflowTemplate template = getById(id);
        if (template.hasVisualBpmn()) {
            log.debug("Returning stored BPMN XML for visual template id={}", id);
            return template.getBpmnXml();
        }
        log.debug("Generating BPMN from DSL for template id={}", id);
        return bpmnGenerator.generate(template.getDsl(objectMapper));
    }

    // ─── Publish ─────────────────────────────────────────────────────────────

    /**
     * Publish: resolve the BPMN XML (stored or generated), deploy to Flowable,
     * mark the template PUBLISHED, and upsert a WorkflowDefinitionConfig row.
     *
     * <p>The WorkflowDefinitionConfig upsert is the key fix: WorkflowInstanceService
     * .startFromTemplate() looks up a WorkflowDefinitionConfig by processKey as a
     * required FK on WorkflowInstanceRecord. Without this step, every document upload
     * that triggers the listener throws:
     * <pre>
     *   IllegalStateException: No WorkflowDefinitionConfig found for processKey: &lt;key&gt;
     * </pre>
     */
    @Transactional
    public WorkflowTemplate publish(Integer id) {
        WorkflowTemplate template = getById(id);
        if (template.getStatus() == WorkflowTemplate.Status.PUBLISHED) {
            throw new IllegalStateException("Template is already published.");
        }

        String bpmnXml;

        if (template.hasVisualBpmn()) {
            // Visual designer path — use stored XML directly
            bpmnXml = template.getBpmnXml();
            log.info("Publishing template '{}' using visual BPMN XML", template.getName());
        } else {
            // DSL path — generate BPMN, validate DSL has steps
            WorkflowTemplateDsl dsl = template.getDsl(objectMapper);
            if (dsl.getSteps() == null || dsl.getSteps().isEmpty()) {
                throw new IllegalStateException(
                        "Cannot publish a template with no steps defined. " +
                                "Add steps in the workflow designer or define a DSL.");
            }
            bpmnXml = bpmnGenerator.generate(dsl);
            log.info("Publishing template '{}' using generated BPMN from DSL", template.getName());
        }

        FlowableDeploymentService.DeploymentResult result =
                deploymentService.deploy(bpmnXml, template.getName());

        template.setProcessKey(result.processDefinitionKey());
        template.setFlowableDeploymentId(result.deploymentId());
        template.setFlowableProcessDefId(result.processDefinitionId());
        template.setStatus(WorkflowTemplate.Status.PUBLISHED);
        template.setVersion(result.version());

        WorkflowTemplate saved = templateRepo.save(template);

        // ── Sync WorkflowDefinitionConfig ──────────────────────────────────
        // Create the config row if it does not already exist for this processKey.
        // This is required because WorkflowInstanceService.startFromTemplate()
        // looks up WorkflowDefinitionConfig by processKey as an FK on
        // WorkflowInstanceRecord.  Without this row the listener dead-letters
        // every document.uploaded RabbitMQ message with IllegalStateException.
        //
        // If a row already exists (e.g. back-filled by V5 migration or created
        // manually by an admin) we leave it untouched to preserve any custom
        // group assignments or SLA overrides.
        Optional<WorkflowDefinitionConfig> existing =
                definitionConfigRepo.findByProcessKey(result.processDefinitionKey());

        if (existing.isEmpty()) {
            WorkflowDefinitionConfig config = WorkflowDefinitionConfig.builder()
                    .name(saved.getName())
                    .description("Auto-created on publish of template id=" + saved.getId())
                    .processKey(result.processDefinitionKey())
                    .assignedRole("ECM_BACKOFFICE")   // default; admin can reassign via UI
                    .isActive(true)
                    .slaHours(saved.getSlaHours())
                    .build();
            definitionConfigRepo.save(config);
            log.info("WorkflowDefinitionConfig created for processKey={}", result.processDefinitionKey());
        } else {
            log.debug("WorkflowDefinitionConfig already exists for processKey={} — skipping",
                    result.processDefinitionKey());
        }
        // ───────────────────────────────────────────────────────────────────

        log.info("Template '{}' published → processKey={} v{}",
                saved.getName(), result.processDefinitionKey(), result.version());
        return saved;
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

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private WorkflowTemplate getDraftOrThrow(Integer id) {
        WorkflowTemplate t = getById(id);
        if (t.getStatus() != WorkflowTemplate.Status.DRAFT) {
            throw new IllegalStateException(
                    "Only DRAFT templates can be edited. Deprecate and create a new version.");
        }
        return t;
    }

    /**
     * Best-effort extraction of the process/@name attribute from BPMN XML.
     * Returns empty if the attribute is absent or the XML is malformed.
     */
    private Optional<String> extractProcessName(String bpmnXml) {
        try {
            // Lightweight regex; we avoid full DOM parse for performance
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("<process[^>]+name=\"([^\"]+)\"")
                    .matcher(bpmnXml);
            if (m.find()) {
                String name = m.group(1).trim();
                return name.isBlank() ? Optional.empty() : Optional.of(name);
            }
        } catch (Exception ignored) { /* non-fatal */ }
        return Optional.empty();
    }
}