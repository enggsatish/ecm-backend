package com.ecm.workflow.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

/**
 * Maps a document category_id to a WorkflowDefinitionConfig.
 * When a document with this category is uploaded, the mapped workflow
 * is auto-triggered via RabbitMQ.
 */
@Entity
@Table(name = "category_workflow_mappings", schema = "ecm_workflow")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class CategoryWorkflowMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    /** FK to ecm_core.document_categories.id — cross-schema, no DB constraint */
    @Column(name = "category_id", nullable = false, unique = true)
    private Integer categoryId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workflow_definition_id", nullable = false)
    private WorkflowDefinitionConfig workflowDefinition;

    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;
}
