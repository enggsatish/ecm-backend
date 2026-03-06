package com.ecm.workflow.model.entity;

import com.ecm.workflow.model.dsl.WorkflowTemplateDsl;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(schema = "ecm_workflow", name = "workflow_templates")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkflowTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false, length = 200)
    private String name;

    private String description;

    /** Populated after publish — matches Flowable's processDefinitionKey */
    @Column(name = "process_key", unique = true)
    private String processKey;

    /** Raw JSONB — use getDsl() helper for deserialized access */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "dsl_definition", columnDefinition = "jsonb", nullable = false)
    private String dslDefinition;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private Status status = Status.DRAFT;

    @Column(nullable = false)
    @Builder.Default
    private Integer version = 1;

    @Column(name = "is_default", nullable = false)
    @Builder.Default
    private boolean isDefault = false;

    // SLA config
    @Column(name = "sla_hours", nullable = false)
    @Builder.Default
    private Integer slaHours = 48;

    @Column(name = "warning_threshold_pct", nullable = false)
    @Builder.Default
    private Integer warningThresholdPct = 80;

    @Column(name = "escalation_hours")
    private Integer escalationHours;

    @Column(name = "escalation_group_key", length = 100)
    private String escalationGroupKey;

    // Flowable deployment refs (set on publish)
    @Column(name = "flowable_deployment_id", length = 200)
    private String flowableDeploymentId;

    @Column(name = "flowable_process_def_id", length = 200)
    private String flowableProcessDefId;

    @Column(name = "created_by", length = 200)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    public void onUpdate() { this.updatedAt = LocalDateTime.now(); }

    /** Convenience deserializer */
    public WorkflowTemplateDsl getDsl(ObjectMapper mapper) {
        try {
            return mapper.readValue(this.dslDefinition, WorkflowTemplateDsl.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize DSL for template: " + id, e);
        }
    }

    public enum Status { DRAFT, PUBLISHED, DEPRECATED }
}