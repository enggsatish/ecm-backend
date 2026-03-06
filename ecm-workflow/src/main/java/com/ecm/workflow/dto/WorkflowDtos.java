package com.ecm.workflow.dto;

import com.ecm.workflow.model.entity.WorkflowInstanceRecord;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

// ── Request DTOs ─────────────────────────────────────────────────────────────

public class WorkflowDtos {

    /**
     * Manual workflow start request — uploader picks a workflow type.
     */
    public record StartWorkflowRequest(
            @NotNull UUID documentId,
            @NotBlank String documentName,
            @NotNull Integer workflowDefinitionId,
            Integer categoryId
    ) {}

    /**
     * Task action — approve, reject, request more info, or pass (triage).
     */
    public record TaskActionRequest(
            /**
             * APPROVED | REJECTED | REQUEST_INFO | PASS
             */
            @NotBlank String decision,
            /**
             * Required when decision = REJECTED or REQUEST_INFO.
             */
            String comment
    ) {}

    /**
     * Submitter provides additional information after REQUEST_INFO decision.
     */
    public record ProvideInfoRequest(
            @NotBlank String comment
    ) {}

    // ── Admin request DTOs (moved from WorkflowDefinitionController inner records) ──

    /** Add a user to a workflow group. */
    public record AddMemberRequest(@NotNull Integer userId) {}

    /** Create a category → workflow mapping. */
    public record CreateCategoryMappingRequest(
            @NotNull Integer categoryId,
            @NotNull Integer workflowDefinitionId
    ) {}

    /** Create or update a workflow definition. */
    public record WorkflowDefinitionRequest(
            @NotBlank String name,
            String description,
            @NotBlank String processKey,
            String assignedRole,
            Integer assignedGroupId,
            Integer slaHours,
            Boolean active
    ) {}

    /** Create a workflow group. */
    public record WorkflowGroupRequest(
            @NotBlank String name,
            String description
    ) {}

// ── Response DTOs ─────────────────────────────────────────────────────────────

    /**
     * Summary of a workflow instance — used in list views.
     */
    public record WorkflowInstanceDto(
            UUID id,
            String processInstanceId,
            UUID documentId,
            String documentName,
            Integer categoryId,
            Integer workflowDefinitionId,
            String workflowDefinitionName,
            WorkflowInstanceRecord.Status status,
            WorkflowInstanceRecord.TriggerType triggerType,
            String startedByEmail,
            OffsetDateTime createdAt,
            OffsetDateTime completedAt,
            String finalComment
    ) {}

    /**
     * A single task in the review queue.
     */
    public record WorkflowTaskDto(
            String taskId,
            String taskName,
            String taskDescription,
            String processInstanceId,
            String assignee,
            List<String> candidateGroups,
            String documentId,
            String documentName,
            OffsetDateTime createTime,
            String status   // PENDING | CLAIMED | INFO_REQUESTED
    ) {}

    /**
     * Workflow definition config — used in the "start workflow" dropdown.
     */
    public record WorkflowDefinitionDto(
            Integer id,
            String name,
            String description,
            String processKey,
            String assignedRole,
            Integer assignedGroupId,
            String assignedGroupName,
            Integer slaHours,
            boolean active
    ) {}

    /**
     * A workflow group for admin management.
     */
    public record WorkflowGroupDto(
            Integer id,
            String name,
            String description,
            String groupKey,
            boolean active,
            int memberCount
    ) {}

    /**
     * A category → workflow mapping entry.
     */
    public record CategoryMappingDto(
            Integer id,
            Integer categoryId,
            Integer workflowDefinitionId,
            String workflowDefinitionName,
            Boolean active
    ) {}

    // ── SLA DTOs ──────────────────────────────────────────────────────
    public record SlaSummaryDto(
            long ON_TRACK,
            long WARNING,
            long ESCALATED,
            long BREACHED) {}

    public record SlaOverdueItemDto(
            Integer id,
            UUID    workflowInstanceId,
            String  templateName,
            String  status,
            LocalDateTime slaDeadline,
            LocalDateTime escalationDeadline,
            String  escalationGroupKey) {}

}