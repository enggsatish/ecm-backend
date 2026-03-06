package com.ecm.workflow.service;

import com.ecm.common.exception.ResourceNotFoundException;
import com.ecm.workflow.dto.WorkflowDtos.*;
import com.ecm.workflow.model.entity.CategoryWorkflowMapping;
import com.ecm.workflow.model.entity.WorkflowDefinitionConfig;
import com.ecm.workflow.model.entity.WorkflowGroup;
import com.ecm.workflow.model.entity.WorkflowGroupMember;
import com.ecm.workflow.repository.CategoryWorkflowMappingRepository;
import com.ecm.workflow.repository.WorkflowDefinitionConfigRepository;
import com.ecm.workflow.repository.WorkflowGroupMemberRepository;
import com.ecm.workflow.repository.WorkflowGroupRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Admin service for workflow configuration CRUD.
 *
 * Owns all write operations for:
 *  - WorkflowDefinitionConfigs (the named workflow types that can be started)
 *  - WorkflowGroups (Flowable candidate groups — team-based task routing)
 *  - CategoryWorkflowMappings (document category → auto-trigger workflow)
 *
 * These operations are driven by ecm-admin at config time, not on every request.
 *
 * The group key double-save issue is fixed here:
 * createGroup() saves once, derives the key from the generated ID, and saves
 * again within a single @Transactional — if either save fails both roll back.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowAdminService {

    private final WorkflowDefinitionConfigRepository definitionRepo;
    private final WorkflowGroupRepository            groupRepo;
    private final WorkflowGroupMemberRepository      memberRepo;
    private final CategoryWorkflowMappingRepository  categoryMappingRepo;

    // ── Workflow Definitions ──────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<WorkflowDefinitionDto> listActiveDefinitions() {
        return definitionRepo.findByIsActiveTrue().stream()
                .map(this::toDefinitionDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<WorkflowDefinitionDto> listAllDefinitions() {
        return definitionRepo.findAll().stream()
                .map(this::toDefinitionDto)
                .toList();
    }

    @Transactional
    public WorkflowDefinitionDto createDefinition(WorkflowDefinitionRequest req) {
        WorkflowDefinitionConfig def = WorkflowDefinitionConfig.builder()
                .name(req.name())
                .description(req.description())
                .processKey(req.processKey())
                .assignedRole(req.assignedRole() != null ? req.assignedRole() : "ECM_BACKOFFICE")
                .assignedGroup(resolveGroup(req.assignedGroupId()))
                .slaHours(req.slaHours())
                .build();

        def = definitionRepo.save(def);
        log.info("Workflow definition created: id={}, name={}", def.getId(), def.getName());
        return toDefinitionDto(def);
    }

    @Transactional
    public WorkflowDefinitionDto updateDefinition(Integer id, WorkflowDefinitionRequest req) {
        WorkflowDefinitionConfig def = definitionRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowDefinition", id));

        def.setName(req.name());
        def.setDescription(req.description());
        def.setAssignedRole(req.assignedRole() != null ? req.assignedRole() : def.getAssignedRole());
        def.setSlaHours(req.slaHours());
        if (req.active() != null) def.setIsActive(req.active());
        def.setAssignedGroup(resolveGroup(req.assignedGroupId()));

        def = definitionRepo.save(def);
        log.info("Workflow definition updated: id={}", id);
        return toDefinitionDto(def);
    }

    // ── Workflow Groups ───────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<WorkflowGroupDto> listGroups() {
        return groupRepo.findAll().stream()
                .map(this::toGroupDto)
                .toList();
    }

    /**
     * Creates a workflow group.
     *
     * The groupKey is derived from the generated DB id ("group:{id}") and cannot
     * be set by the caller. Two saves are unavoidable since we need the generated
     * ID first, but both happen within this single @Transactional — any failure
     * rolls back both, leaving no orphaned rows.
     */
    @Transactional
    public WorkflowGroupDto createGroup(WorkflowGroupRequest req) {
        // First save to get the generated ID
        WorkflowGroup group = WorkflowGroup.builder()
                .name(req.name())
                .description(req.description())
                .groupKey("pending")   // temporary — replaced immediately below
                .build();
        group = groupRepo.save(group);

        // Derive key from generated ID — this is the Flowable candidateGroup string
        group.setGroupKey("group:" + group.getId());
        group = groupRepo.save(group);

        log.info("Workflow group created: id={}, key={}", group.getId(), group.getGroupKey());
        return toGroupDto(group);
    }

    @Transactional
    public void addMember(Integer groupId, Integer userId) {
        WorkflowGroup group = groupRepo.findById(groupId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowGroup", groupId));

        WorkflowGroupMember member = WorkflowGroupMember.builder()
                .group(group)
                .userId(userId)
                .build();
        memberRepo.save(member);
        log.info("User {} added to workflow group {}", userId, groupId);
    }

    @Transactional
    public void removeMember(Integer groupId, Integer userId) {
        memberRepo.deleteByGroupIdAndUserId(groupId, userId);
        log.info("User {} removed from workflow group {}", userId, groupId);
    }

    // ── Category Workflow Mappings ────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<CategoryMappingDto> listCategoryMappings() {
        return categoryMappingRepo.findAll().stream()
                .map(m -> new CategoryMappingDto(
                        m.getId(),
                        m.getCategoryId(),
                        m.getWorkflowDefinition().getId(),
                        m.getWorkflowDefinition().getName(),
                        m.getIsActive()))
                .toList();
    }

    @Transactional
    public CategoryMappingDto createCategoryMapping(CreateCategoryMappingRequest req) {
        WorkflowDefinitionConfig def = definitionRepo.findById(req.workflowDefinitionId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "WorkflowDefinition", req.workflowDefinitionId()));

        CategoryWorkflowMapping mapping = CategoryWorkflowMapping.builder()
                .categoryId(req.categoryId())
                .workflowDefinition(def)
                .build();
        mapping = categoryMappingRepo.save(mapping);

        log.info("Category mapping created: categoryId={} → workflow={}",
                req.categoryId(), def.getName());
        return new CategoryMappingDto(
                mapping.getId(), mapping.getCategoryId(),
                def.getId(), def.getName(), true);
    }

    @Transactional
    public void deleteCategoryMapping(Integer id) {
        categoryMappingRepo.deleteById(id);
        log.info("Category mapping deleted: id={}", id);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private WorkflowGroup resolveGroup(Integer groupId) {
        if (groupId == null) return null;
        return groupRepo.findById(groupId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowGroup", groupId));
    }

    private WorkflowDefinitionDto toDefinitionDto(WorkflowDefinitionConfig def) {
        WorkflowGroup group = def.getAssignedGroup();
        return new WorkflowDefinitionDto(
                def.getId(), def.getName(), def.getDescription(),
                def.getProcessKey(), def.getAssignedRole(),
                group != null ? group.getId()   : null,
                group != null ? group.getName() : null,
                def.getSlaHours(),
                Boolean.TRUE.equals(def.getIsActive()));
    }

    private WorkflowGroupDto toGroupDto(WorkflowGroup g) {
        return new WorkflowGroupDto(
                g.getId(), g.getName(), g.getDescription(),
                g.getGroupKey(), Boolean.TRUE.equals(g.getIsActive()),
                g.getMembers().size());
    }
}
