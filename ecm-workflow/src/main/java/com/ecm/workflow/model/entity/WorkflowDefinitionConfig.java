package com.ecm.workflow.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for a deployable workflow type.
 *
 * Links a Flowable BPMN process key to:
 *  - A human-readable name and description
 *  - Assignment rules: assigned_role (fallback) OR assigned_group (specific team)
 *  - Category mappings (which document categories trigger this workflow)
 *
 * Example rows:
 *   "General Document Review"  → process_key=document-single-review, role=ECM_BACKOFFICE
 *   "Underwriter Review"       → process_key=document-dual-review,   group=Underwriting Team
 *   "Compliance Review"        → process_key=document-single-review, role=ECM_REVIEWER
 */
@Entity
@Table(name = "workflow_definition_configs", schema = "ecm_workflow")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class WorkflowDefinitionConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 500)
    private String description;

    /**
     * Flowable BPMN process definition key.
     * Must match the id= attribute in the .bpmn20.xml file.
     */
    @Column(name = "process_key", nullable = false, length = 100)
    private String processKey;

    /**
     * Fallback role for task assignment when assigned_group_id is null.
     * Stored as Okta group name WITHOUT the ROLE_ prefix, e.g. "ECM_BACKOFFICE".
     * Flowable receives this directly as the candidateGroup string.
     */
    @Column(name = "assigned_role", nullable = false, length = 100)
    @Builder.Default
    private String assignedRole = "ECM_BACKOFFICE";

    /**
     * Optional specific group override. When set, overrides assigned_role.
     * group.groupKey is passed to Flowable as candidateGroup.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_group_id")
    private WorkflowGroup assignedGroup;

    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    /** Expected completion time in hours (informational, for SLA dashboard) */
    @Column(name = "sla_hours")
    private Integer slaHours;

    @OneToMany(mappedBy = "workflowDefinition", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<CategoryWorkflowMapping> categoryMappings = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    /**
     * Resolves the candidateGroup string to pass to Flowable.
     * Group override wins over role fallback.
     */
    public String resolveCandidateGroup() {
        if (assignedGroup != null && Boolean.TRUE.equals(assignedGroup.getIsActive())) {
            return assignedGroup.getGroupKey();
        }
        return assignedRole;
    }
}
