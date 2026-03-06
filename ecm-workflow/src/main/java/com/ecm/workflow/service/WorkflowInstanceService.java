package com.ecm.workflow.service;

import com.ecm.common.exception.ResourceNotFoundException;
import com.ecm.workflow.dto.WorkflowDtos.*;
import com.ecm.workflow.model.dsl.WorkflowTemplateDsl;
import com.ecm.workflow.model.entity.WorkflowDefinitionConfig;
import com.ecm.workflow.model.entity.WorkflowInstanceRecord;
import com.ecm.workflow.model.entity.WorkflowInstanceRecord.Status;
import com.ecm.workflow.model.entity.WorkflowInstanceRecord.TriggerType;
import com.ecm.workflow.model.entity.WorkflowTemplate;
import com.ecm.workflow.repository.WorkflowDefinitionConfigRepository;
import com.ecm.workflow.repository.WorkflowInstanceRecordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.ProcessInstance;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Manages workflow instance lifecycle:
 *   - Start (manual or auto-triggered)
 *   - List (role-filtered)
 *   - Mark complete (called by processCompletedListener)
 *   - Cancel
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowInstanceService {

    // Add to existing field declarations (Lombok @RequiredArgsConstructor picks these up)
    private final RuntimeService runtimeService;
    private final ObjectMapper objectMapper;
    private final WorkflowInstanceRecordRepository instanceRecordRepo;
    private final WorkflowDefinitionConfigRepository definitionConfigRepo;
    private final TaskService  taskService;
    private final WorkflowDefinitionConfigRepository workflowDefinitionConfigRepo;

    // ── Start workflow ────────────────────────────────────────────────────

    /**
     * Manual start — called when a user clicks "Start Review" in the UI.
     */
    @Transactional
    public WorkflowInstanceDto startManual(StartWorkflowRequest req,
                                           String startedBySubject,
                                           String startedByEmail) {
        WorkflowDefinitionConfig def = definitionConfigRepo.findById(req.workflowDefinitionId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "WorkflowDefinition", req.workflowDefinitionId()));

        return start(req.documentId(), req.documentName(), req.categoryId(),
                     def, TriggerType.MANUAL, startedBySubject, startedByEmail);
    }

    /**
     * Auto start — called by DocumentUploadedListener when category mapping found.
     */
    @Transactional
    public WorkflowInstanceDto startAuto(UUID documentId,
                                          String documentName,
                                          Integer categoryId,
                                          WorkflowDefinitionConfig def,
                                          String startedBySubject,
                                          String startedByEmail) {
        return start(documentId, documentName, categoryId,
                     def, TriggerType.AUTO, startedBySubject, startedByEmail);
    }

    private WorkflowInstanceDto start(UUID documentId,
                                       String documentName,
                                       Integer categoryId,
                                       WorkflowDefinitionConfig def,
                                       TriggerType triggerType,
                                       String startedBySubject,
                                       String startedByEmail) {

        String candidateGroup = def.resolveCandidateGroup();

        // Create our record first (we need its ID as a process variable)
        WorkflowInstanceRecord record = WorkflowInstanceRecord.builder()
                .documentId(documentId)
                .documentName(documentName)
                .categoryId(categoryId)
                .workflowDefinition(def)
                .status(Status.ACTIVE)
                .triggerType(triggerType)
                .startedBySubject(startedBySubject)
                .startedByEmail(startedByEmail)
                .processInstanceId("PENDING") // temporary, updated below
                .build();
        record = instanceRecordRepo.save(record);

        // Process variables passed into the BPMN
        Map<String, Object> vars = new HashMap<>();
        vars.put("documentId",       documentId.toString());
        vars.put("documentName",     documentName != null ? documentName : "");
        vars.put("candidateGroup",   candidateGroup);
        vars.put("startedBy",        startedBySubject);
        vars.put("instanceRecordId", record.getId().toString());

        // Start the Flowable process
        ProcessInstance pi = runtimeService.startProcessInstanceByKey(
                def.getProcessKey(),
                "doc:" + documentId,   // business key for easy lookup
                vars);

        // Update record with real Flowable process instance ID
        record.setProcessInstanceId(pi.getId());
        record = instanceRecordRepo.save(record);

        log.info("Workflow started: processInstanceId={}, documentId={}, trigger={}, group={}",
                pi.getId(), documentId, triggerType, candidateGroup);

        return toDto(record);
    }

    // ── List ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<WorkflowInstanceDto> listAll(Pageable pageable) {
        return instanceRecordRepo.findAllByOrderByCreatedAtDesc(pageable).map(this::toDto);
    }

    @Transactional(readOnly = true)
    public Page<WorkflowInstanceDto> listActive(Pageable pageable) {
        return instanceRecordRepo
                .findByStatusOrderByCreatedAtDesc(Status.ACTIVE, pageable)
                .map(this::toDto);
    }

    @Transactional(readOnly = true)
    public List<WorkflowInstanceDto> listByDocument(UUID documentId) {
        return instanceRecordRepo.findByDocumentId(documentId)
                .stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public Page<WorkflowInstanceDto> listMyInstances(String subject, Pageable pageable) {
        return instanceRecordRepo
                .findByStartedBySubjectOrderByCreatedAtDesc(subject, pageable)
                .map(this::toDto);
    }

    @Transactional(readOnly = true)
    public WorkflowInstanceDto getById(UUID id) {
        return instanceRecordRepo.findById(id)
                .map(this::toDto)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowInstance", id));
    }

    // ── Update ────────────────────────────────────────────────────────────

    /**
     * Called by processCompletedListener when a Flowable end event fires.
     */
    @Transactional
    public void markCompleted(String processInstanceId, String decision, String comment) {
        WorkflowInstanceRecord record = instanceRecordRepo
                .findByProcessInstanceId(processInstanceId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "WorkflowInstance by processInstanceId", processInstanceId));

        Status newStatus = "REJECTED".equals(decision)
                ? Status.COMPLETED_REJECTED
                : Status.COMPLETED_APPROVED;

        record.setStatus(newStatus);
        record.setCompletedAt(OffsetDateTime.now());
        record.setFinalComment(comment);
        instanceRecordRepo.save(record);

        log.info("Workflow marked {}: processInstanceId={}", newStatus, processInstanceId);
    }

    /**
     * Called when a reviewer selects REQUEST_INFO — marks the instance so the
     * submitter can see they have a pending information request.
     */
    @Transactional
    public void markInfoRequested(String processInstanceId) {
        instanceRecordRepo.findByProcessInstanceId(processInstanceId)
                .ifPresent(record -> {
                    record.setStatus(Status.INFO_REQUESTED);
                    instanceRecordRepo.save(record);
                    log.info("Workflow marked INFO_REQUESTED: processInstanceId={}", processInstanceId);
                });
    }

    /**
     * Called when the submitter provides the requested info — transitions
     * the instance back to ACTIVE so it reappears in the reviewer queue.
     */
    @Transactional
    public void markInfoProvided(String processInstanceId) {
        instanceRecordRepo.findByProcessInstanceId(processInstanceId)
                .ifPresent(record -> {
                    record.setStatus(Status.ACTIVE);
                    instanceRecordRepo.save(record);
                    log.info("Workflow back to ACTIVE after info provided: processInstanceId={}", processInstanceId);
                });
    }

    /**
     * Cancel a running workflow — admin only.
     */
    @Transactional
    public void cancel(UUID id, String cancelledBySubject) {
        WorkflowInstanceRecord record = instanceRecordRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowInstance", id));

        if (record.getStatus() != Status.ACTIVE) {
            throw new IllegalStateException(
                    "Cannot cancel a workflow that is not ACTIVE. Current: " + record.getStatus());
        }

        // Tell Flowable to delete the process instance
        runtimeService.deleteProcessInstance(
                record.getProcessInstanceId(),
                "Cancelled by " + cancelledBySubject);

        record.setStatus(Status.CANCELLED);
        record.setCompletedAt(OffsetDateTime.now());
        instanceRecordRepo.save(record);

        log.info("Workflow cancelled: id={}, by={}", id, cancelledBySubject);
    }

    // ── Mapping ───────────────────────────────────────────────────────────

    private WorkflowInstanceDto toDto(WorkflowInstanceRecord r) {
        WorkflowDefinitionConfig def = r.getWorkflowDefinition();
        return new WorkflowInstanceDto(
                r.getId(),
                r.getProcessInstanceId(),
                r.getDocumentId(),
                r.getDocumentName(),
                r.getCategoryId(),
                def != null ? def.getId() : null,
                def != null ? def.getName() : null,
                r.getStatus(),
                r.getTriggerType(),
                r.getStartedByEmail(),
                r.getCreatedAt(),
                r.getCompletedAt(),
                r.getFinalComment()
        );
    }

    /**
     * Submitter provides additional information after a REQUEST_INFO decision.
     * Finds the suspended/waiting task on this process instance and completes it
     * with decision=INFO_PROVIDED so the process loops back to the reviewer pool.
     *
     * @param instanceId         UUID of our WorkflowInstanceRecord
     * @param comment            Submitter's response / additional info
     * @param submitterSubject   Okta subject of the submitter
     */
    @Transactional
    public WorkflowInstanceDto provideInfo(UUID instanceId, String comment, String submitterSubject) {

        WorkflowInstanceRecord record = instanceRecordRepo.findById(instanceId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowInstance", instanceId));

        if (record.getStatus() != WorkflowInstanceRecord.Status.INFO_REQUESTED) {
            throw new IllegalStateException(
                    "Workflow instance " + instanceId + " is not in INFO_REQUESTED state");
        }

        // Find the active task on this process instance
        org.flowable.task.api.Task task = taskService.createTaskQuery()
                .processInstanceId(record.getProcessInstanceId())
                .singleResult();

        if (task == null) {
            throw new IllegalStateException(
                    "No active task found for process instance: " + record.getProcessInstanceId());
        }

        // Complete the task — the BPMN loop routes it back to the reviewer pool
        Map<String, Object> variables = new HashMap<>();
        variables.put("decision", "INFO_PROVIDED");
        variables.put("comment", comment);
        variables.put("infoProvidedBy", submitterSubject);

        taskService.complete(task.getId(), variables);

        // Update our record back to ACTIVE (waiting for reviewer)
        record.setStatus(WorkflowInstanceRecord.Status.ACTIVE);
        record = instanceRecordRepo.save(record);

        log.info("Info provided for workflow instance: {}, by: {}", instanceId, submitterSubject);
        return toDto(record);
    }

    /**
     * Starts a new workflow process instance driven by a resolved WorkflowTemplate.
     * Called by DocumentUploadedListener after template resolution.
     *
     * Process variables injected into Flowable:
     *  - All variables from template DSL (e.g. reviewerGroup, seniorGroup)
     *  - documentId   → the ECM document UUID string
     *  - initiator    → uploadedBy (Okta subject — used by INFO_WAIT tasks)
     *  - templateId   → template PK for traceability
     *
     * @param documentId  ECM document identifier
     * @param template    Resolved published WorkflowTemplate
     * @param uploadedBy  Okta subject of the user who uploaded the document
     * @return UUID of the persisted WorkflowInstanceRecord
     */
    @Transactional
    public UUID startFromTemplate(String documentId, WorkflowTemplate template, String uploadedBy) {

        if (template.getStatus() != WorkflowTemplate.Status.PUBLISHED) {
            throw new IllegalStateException(
                    "Cannot start workflow from unpublished template: " + template.getId());
        }
        if (template.getProcessKey() == null || template.getProcessKey().isBlank()) {
            throw new IllegalStateException(
                    "Template has no Flowable processKey — was it published? templateId="
                            + template.getId());
        }

        // Resolve WorkflowDefinitionConfig by processKey — required FK on the record
        WorkflowDefinitionConfig definitionConfig = workflowDefinitionConfigRepo
                .findByProcessKey(template.getProcessKey())
                .orElseThrow(() -> new IllegalStateException(
                        "No WorkflowDefinitionConfig found for processKey: "
                                + template.getProcessKey()
                                + ". Ensure the template was published and definition synced."));

        // Build Flowable process variables
        Map<String, Object> processVariables = new HashMap<>();
        WorkflowTemplateDsl dsl = template.getDsl(objectMapper);
        if (dsl.getVariables() != null) {
            processVariables.putAll(dsl.getVariables());
        }
        processVariables.put("documentId", documentId);
        processVariables.put("initiator",  uploadedBy);
        processVariables.put("templateId", template.getId());

        // Start the Flowable process
        org.flowable.engine.runtime.ProcessInstance flowableInstance =
                runtimeService.startProcessInstanceByKey(
                        template.getProcessKey(),
                        "DOC-" + documentId,
                        processVariables);

        // Persist tracking record — field names match entity exactly
        WorkflowInstanceRecord record = WorkflowInstanceRecord.builder()
                .processInstanceId(flowableInstance.getId())
                .documentId(UUID.fromString(documentId))          // String → UUID
                .workflowDefinition(definitionConfig)             // required FK
                .startedBySubject(uploadedBy)                     // correct field name
                .triggerType(WorkflowInstanceRecord.TriggerType.AUTO)
                .status(WorkflowInstanceRecord.Status.ACTIVE)
                .templateId(template.getId())
                .build();

        record = instanceRecordRepo.save(record);

        log.info("Workflow started from template '{}' for document={}, instanceId={}",
                template.getName(), documentId, record.getId());

        return record.getId();
    }
}
