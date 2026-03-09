package com.ecm.workflow.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Immutable audit row written on every task lifecycle action.
 * Action values: CLAIMED | RELEASED | APPROVED | REJECTED | INFO_REQUESTED | INFO_PROVIDED
 */
@Entity
@Table(schema = "ecm_workflow", name = "workflow_task_history")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkflowTaskHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false, length = 64)
    private String taskId;

    @Column(name = "process_instance_id", nullable = false, length = 64)
    private String processInstanceId;

    @Column(name = "document_id")
    private UUID documentId;

    /** CLAIMED | RELEASED | APPROVED | REJECTED | INFO_REQUESTED | INFO_PROVIDED */
    @Column(nullable = false, length = 30)
    private String action;

    @Column(name = "actor_subject", nullable = false, length = 200)
    private String actorSubject;

    @Column(name = "actor_email", length = 200)
    private String actorEmail;

    @Column(columnDefinition = "TEXT")
    private String comment;

    @Column(name = "sla_deadline")
    private OffsetDateTime slaDeadline;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
