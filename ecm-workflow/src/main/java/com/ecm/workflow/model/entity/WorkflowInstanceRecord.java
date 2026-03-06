package com.ecm.workflow.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Our own tracking record for workflow instances.
 *
 * Bridges Flowable's opaque process_instance_id to ECM domain concepts:
 *   - document_id (UUID from ecm_core.documents)
 *   - document_name (denormalised — avoids cross-service call for display)
 *   - status (our enum, updated by processCompletedListener)
 *   - started_by_subject (Okta JWT sub)
 *
 * This table is the source of truth for the frontend's workflow list views.
 * Flowable's ACT_HI_* history tables hold the detailed task/audit trail.
 */
@Entity
@Table(name = "workflow_instance_records", schema = "ecm_workflow")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class WorkflowInstanceRecord {

    public enum Status {
        ACTIVE,
        INFO_REQUESTED,        // Reviewer requested more info from submitter
        COMPLETED_APPROVED,
        COMPLETED_REJECTED,
        CANCELLED
    }

    public enum TriggerType {
        MANUAL,   // uploader selected a workflow
        AUTO      // triggered by document category mapping
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    /** Flowable's internal process instance ID — used for all Flowable API calls */
    @Column(name = "process_instance_id", nullable = false, unique = true, length = 100)
    private String processInstanceId;

    /** The document this workflow is reviewing */
    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    /** Denormalised display name — avoids cross-service lookup */
    @Column(name = "document_name", length = 500)
    private String documentName;

    /** Optional: category that triggered the auto-workflow */
    @Column(name = "category_id")
    private Integer categoryId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workflow_definition_id", nullable = false)
    private WorkflowDefinitionConfig workflowDefinition;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private Status status = Status.ACTIVE;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 20)
    @Builder.Default
    private TriggerType triggerType = TriggerType.MANUAL;

    /** Okta JWT sub of the user who triggered (uploaded the document or clicked Start) */
    @Column(name = "started_by_subject", nullable = false, length = 255)
    private String startedBySubject;

    @Column(name = "started_by_email", length = 255)
    private String startedByEmail;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    /** Final reviewer comment — copied from Flowable process variable on completion */
    @Column(name = "final_comment", columnDefinition = "text")
    private String finalComment;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @Column(name = "template_id")
    private Integer templateId;
}
