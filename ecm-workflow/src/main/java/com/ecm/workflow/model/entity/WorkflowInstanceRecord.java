package com.ecm.workflow.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "workflow_instance_records", schema = "ecm_workflow")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class WorkflowInstanceRecord {

    public enum Status {
        ACTIVE,
        INFO_REQUESTED,
        COMPLETED_APPROVED,
        COMPLETED_REJECTED,
        CANCELLED
    }

    public enum TriggerType {
        MANUAL,
        AUTO
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "process_instance_id", nullable = false, unique = true, length = 100)
    private String processInstanceId;

    /**
     * CHANGED: nullable = true  (was false)
     *
     * Form-triggered workflows have no document at submission time.
     * The document UUID is populated only after reviewer APPROVAL fires
     * the WorkflowCompletedListener in ecm-eforms, which creates the document
     * and can optionally call back to update this field.
     *
     * Document-triggered workflows always supply documentId at start.
     */
    @Column(name = "document_id")                       // ← removed nullable = false
    private UUID documentId;

    @Column(name = "document_name", length = 500)
    private String documentName;

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

    @Column(name = "started_by_subject", nullable = false, length = 255)
    private String startedBySubject;

    @Column(name = "started_by_email", length = 255)
    private String startedByEmail;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

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

    /** Okta subject of the form submitter (only for form-triggered workflows) */
    @Column(name = "submission_id", length = 100)       // ← ADD: link back to the FormSubmission
    private String submissionId;
}