package com.ecm.workflow.service;

import com.ecm.workflow.model.dsl.WorkflowTemplateDsl;
import com.ecm.workflow.model.entity.WorkflowDefinitionConfig;
import com.ecm.workflow.model.entity.WorkflowTemplate;
import com.ecm.workflow.model.entity.WorkflowTemplate.BpmnSource;
import com.ecm.workflow.model.entity.WorkflowInstanceRecord;
import com.ecm.workflow.repository.WorkflowDefinitionConfigRepository;
import com.ecm.workflow.repository.WorkflowInstanceRecordRepository;
import com.ecm.workflow.repository.WorkflowTemplateRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowTemplateService {

    private final WorkflowTemplateRepository templateRepo;
    private final BpmnGeneratorService bpmnGenerator;
    private final FlowableDeploymentService deploymentService;
    private final ObjectMapper objectMapper;
    // Injected to sync WorkflowDefinitionConfig on publish — fixes the
    // "No WorkflowDefinitionConfig found for processKey" IllegalStateException
    // that occurs when startFromTemplate() is called by the document upload listener.
    private final WorkflowDefinitionConfigRepository definitionConfigRepo;
    private final WorkflowInstanceRecordRepository instanceRecordRepo;

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
                                   String escalationGroupKey, List<String> tags,
                                   String createdBy) {
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
                    .tags(tags)
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
        if (template.getStatus() == WorkflowTemplate.Status.PUBLISHED
                && template.getFlowableDeploymentId() != null) {
            throw new IllegalStateException("Template is already published and deployed.");
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

        // ── Post-process BPMN XML ────────────────────────────────────────────
        // These transformations ensure visual BPMN templates work correctly
        // with the ECM workflow engine, regardless of what the designer authored.

        // 0. Ensure the BPMN process ID matches the template's stored processKey.
        //    This prevents UNIQUE constraint violations when a cloned template's
        //    BPMN XML still contains the original process ID (e.g., the bpmn-js
        //    designer re-saved its in-memory XML after a rename).
        if (template.getProcessKey() != null && !template.getProcessKey().isBlank()) {
            String targetKey = template.getProcessKey();
            // Extract current process ID from BPMN XML
            Matcher pkMatcher = Pattern.compile("<process\\s+id=\"([^\"]+)\"").matcher(bpmnXml);
            if (pkMatcher.find()) {
                String currentKey = pkMatcher.group(1);
                if (!currentKey.equals(targetKey)) {
                    log.info("Rewriting BPMN process ID: '{}' → '{}'", currentKey, targetKey);
                    bpmnXml = bpmnXml.replace("id=\"" + currentKey + "\"", "id=\"" + targetKey + "\"");
                    bpmnXml = bpmnXml.replace("bpmnElement=\"" + currentKey + "\"", "bpmnElement=\"" + targetKey + "\"");
                }
            }
        }

        // 1. Replace hardcoded candidate groups with ${candidateGroup} process variable
        //    ONLY when all userTasks use the SAME group (simple single-review workflows).
        //    Multi-role workflows (triage) intentionally use different groups per task.
        java.util.Set<String> distinctGroups = new java.util.HashSet<>();
        Matcher cgMatcher = Pattern.compile("flowable:candidateGroups=\"(ECM_[A-Z_]+)\"").matcher(bpmnXml);
        while (cgMatcher.find()) distinctGroups.add(cgMatcher.group(1));

        if (distinctGroups.size() == 1) {
            // All tasks use the same group — safe to replace with runtime variable
            bpmnXml = bpmnXml.replaceAll(
                    "flowable:candidateGroups=\"ECM_[A-Z_]+\"",
                    Matcher.quoteReplacement("flowable:candidateGroups=\"${candidateGroup}\""));
        } else if (distinctGroups.size() > 1) {
            // Multiple different groups — keep them as-is (multi-role workflow)
            log.info("Multi-role BPMN detected ({} distinct groups) — keeping hardcoded candidateGroups",
                    distinctGroups.size());
        }

        // 2. Inject task listeners on userTasks that don't have them
        //    - taskCreatedListener: publishes workflow.task.assigned → triggers notifications
        //    - taskCompletedListener: logs task completion for audit
        if (!bpmnXml.contains("taskCreatedListener")) {
            String taskListenerBlock =
                    "      <extensionElements>\n" +
                    "          <flowable:taskListener event=\"create\" " +
                    "delegateExpression=\"${taskCreatedListener}\"/>\n" +
                    "          <flowable:taskListener event=\"complete\" " +
                    "delegateExpression=\"${taskCompletedListener}\"/>\n" +
                    "        </extensionElements>\n" +
                    "      </userTask>";
            // Replace self-closing userTasks: <userTask ... />
            bpmnXml = bpmnXml.replace(
                    "flowable:formFieldValidation=\"false\" />",
                    "flowable:formFieldValidation=\"false\">\n" + taskListenerBlock);
            if (!bpmnXml.contains("taskCompletedListener")) {
                log.warn("Could not inject task listeners — userTask format not recognized");
            }
        }

        // 3. Inject ProcessEndListener on endEvents that don't have it
        if (!bpmnXml.toLowerCase().contains("processendlistener")) {
            // Match <endEvent id="..." name="..." /> (self-closing)
            Pattern endEventPattern = Pattern.compile(
                    "<endEvent\\s+id=\"([^\"]+)\"\\s+name=\"([^\"]*)\"\\s*/>");
            Matcher m = endEventPattern.matcher(bpmnXml);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String groupId = m.group(1);
                String name = m.group(2);
                String replacement =
                        "<endEvent id=\"" + groupId + "\" name=\"" + name + "\">\n" +
                        "        <extensionElements>\n" +
                        "          <flowable:executionListener event=\"end\"\n" +
                        "              class=\"com.ecm.workflow.flowable.ProcessEndListener\">\n" +
                        "            <flowable:field name=\"completionStatus\" stringValue=\"" + name + "\"/>\n" +
                        "          </flowable:executionListener>\n" +
                        "        </extensionElements>\n" +
                        "      </endEvent>";
                m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            }
            m.appendTail(sb);
            bpmnXml = sb.toString();
        }

        FlowableDeploymentService.DeploymentResult result =
                deploymentService.deploy(bpmnXml, template.getName());

        template.setProcessKey(result.processDefinitionKey());
        template.setFlowableDeploymentId(result.deploymentId());
        template.setFlowableProcessDefId(result.processDefinitionId());
        template.setStatus(WorkflowTemplate.Status.PUBLISHED);
        // version is managed by our own counter (incremented on clone), not Flowable's

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

    /**
     * Delete a workflow template.
     * DRAFT templates are deleted immediately.
     * PUBLISHED/DEPRECATED templates are only deleted if no ACTIVE workflow instances exist.
     * Also cleans up the associated WorkflowDefinitionConfig if present.
     */
    @Transactional
    public void delete(Integer id) {
        WorkflowTemplate template = getById(id);

        // DRAFT — always safe to delete
        if (template.getStatus() != WorkflowTemplate.Status.DRAFT) {
            // Check for active instances
            long activeCount = instanceRecordRepo.countByTemplateIdAndStatus(
                    id, WorkflowInstanceRecord.Status.ACTIVE);
            if (activeCount > 0) {
                throw new IllegalStateException(
                        "Cannot delete template — " + activeCount + " active workflow instance(s) " +
                        "are still running. Complete or cancel them first.");
            }
        }

        // Clean up WorkflowDefinitionConfig if one exists for this processKey
        if (template.getProcessKey() != null) {
            definitionConfigRepo.findByProcessKey(template.getProcessKey())
                    .ifPresent(config -> {
                        definitionConfigRepo.delete(config);
                        log.info("Deleted WorkflowDefinitionConfig for processKey={}", template.getProcessKey());
                    });
        }

        templateRepo.delete(template);
        log.info("Deleted workflow template id={}, processKey={}, status={}",
                id, template.getProcessKey(), template.getStatus());
    }

    /**
     * Clone a template (any status) into a new DRAFT.
     * Copies DSL, BPMN XML, SLA settings, and escalation config.
     * Generates a new unique processKey to avoid UNIQUE constraint violations.
     * The new draft has no Flowable deployment — it must be published separately.
     */
    @Transactional
    public WorkflowTemplate clone(Integer sourceId, String clonedBy) {
        WorkflowTemplate source = getById(sourceId);

        // Generate a unique process key by appending a short timestamp suffix
        String suffix = "-v" + System.currentTimeMillis() % 100000;
        String oldKey = source.getProcessKey();
        String newKey = oldKey != null ? oldKey + suffix : null;

        // Rewrite process key in BPMN XML so publish() deploys under the new key
        String bpmnXml = source.getBpmnXml();
        if (bpmnXml != null && oldKey != null) {
            bpmnXml = bpmnXml.replace(
                    "id=\"" + oldKey + "\"",
                    "id=\"" + newKey + "\"");
            // Also update bpmnElement references in the diagram
            bpmnXml = bpmnXml.replace(
                    "bpmnElement=\"" + oldKey + "\"",
                    "bpmnElement=\"" + newKey + "\"");
        }

        // Rewrite process key in DSL definition
        String dslDef = source.getDslDefinition();
        if (dslDef != null && oldKey != null) {
            dslDef = dslDef.replace("\"" + oldKey + "\"", "\"" + newKey + "\"");
        }

        WorkflowTemplate draft = WorkflowTemplate.builder()
                .name(source.getName() + " (copy)")
                .processKey(newKey)
                .dslDefinition(dslDef)
                .bpmnXml(bpmnXml)
                .bpmnSource(source.getBpmnSource())
                .status(WorkflowTemplate.Status.DRAFT)
                .version(source.getVersion() + 1)
                .slaHours(source.getSlaHours())
                .warningThresholdPct(source.getWarningThresholdPct())
                .escalationHours(source.getEscalationHours())
                .escalationGroupKey(source.getEscalationGroupKey())
                .tags(source.getTags() != null ? new java.util.ArrayList<>(source.getTags()) : null)
                .createdBy(clonedBy)
                .build();

        WorkflowTemplate saved = templateRepo.save(draft);
        log.info("Cloned template id={} → new draft id={} (processKey={}) by {}",
                sourceId, saved.getId(), newKey, clonedBy);
        return saved;
    }

    /**
     * Update name, process key, and/or tags for a DRAFT template.
     */
    @Transactional
    public WorkflowTemplate updateMeta(Integer id, String name, String processKey,
                                       List<String> tags) {
        WorkflowTemplate template = getDraftOrThrow(id);

        String oldKey = template.getProcessKey();

        if (name != null && !name.isBlank()) {
            template.setName(name.trim());
        }

        if (processKey != null && !processKey.isBlank()) {
            String newKey = processKey.trim();

            // Rewrite in BPMN XML
            if (template.getBpmnXml() != null && oldKey != null) {
                template.setBpmnXml(template.getBpmnXml()
                        .replace("id=\"" + oldKey + "\"", "id=\"" + newKey + "\"")
                        .replace("bpmnElement=\"" + oldKey + "\"", "bpmnElement=\"" + newKey + "\""));
            }

            // Rewrite in DSL
            if (template.getDslDefinition() != null && oldKey != null) {
                template.setDslDefinition(
                        template.getDslDefinition().replace("\"" + oldKey + "\"", "\"" + newKey + "\""));
            }

            template.setProcessKey(newKey);
        }

        if (tags != null) {
            template.setTags(tags);
        }

        return templateRepo.save(template);
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
            Matcher m = Pattern
                    .compile("<process[^>]+name=\"([^\"]+)\"")
                    .matcher(bpmnXml);
            if (m.find()) {
                String name = m.group(1).trim();
                return name.isBlank() ? Optional.empty() : Optional.of(name);
            }
        } catch (Exception ignored) { /* non-fatal */ }
        return Optional.empty();
    }

    // ─── Auto-deploy on startup ──────────────────────────────────────────────

    /**
     * Deploys any templates that are PUBLISHED in the DB but not yet deployed
     * to Flowable (flowable_deployment_id IS NULL). This closes the bootstrap
     * gap where init.sql seeds templates with status=PUBLISHED but the Flowable
     * engine has no process definitions registered.
     *
     * Called by WorkflowAutoDeployConfig on ApplicationReadyEvent.
     */
    @Transactional
    public int autoDeployPendingTemplates() {
        List<WorkflowTemplate> pending = templateRepo.findPublishedButNotDeployed();
        if (pending.isEmpty()) return 0;

        int deployed = 0;
        for (WorkflowTemplate template : pending) {
            try {
                log.info("Auto-deploying template: id={}, processKey={}, name={}",
                        template.getId(), template.getProcessKey(), template.getName());
                publish(template.getId());
                deployed++;
            } catch (Exception e) {
                log.error("Auto-deploy failed for template id={}: {}",
                        template.getId(), e.getMessage(), e);
            }
        }
        return deployed;
    }
}