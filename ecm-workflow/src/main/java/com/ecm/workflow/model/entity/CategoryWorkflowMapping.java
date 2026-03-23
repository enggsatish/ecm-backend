package com.ecm.workflow.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Maps a document category to a workflow template.
 * When a document with this categoryId is uploaded, the linked workflow is auto-started.
 * One workflow per category (UNIQUE on category_id).
 */
@Entity
@Table(schema = "ecm_workflow", name = "category_workflow_mappings")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CategoryWorkflowMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "category_id", nullable = false, unique = true)
    private Integer categoryId;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "template_id", nullable = false)
    private WorkflowTemplate template;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "created_by", length = 200)
    private String createdBy;
}
