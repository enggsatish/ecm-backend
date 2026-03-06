package com.ecm.workflow.model.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(schema = "ecm_workflow", name = "workflow_sla_tracking")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkflowSlaTracking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "workflow_instance_id", nullable = false, unique = true)
    private UUID workflowInstanceId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "template_id")
    private WorkflowTemplate template;

    @Column(name = "sla_deadline", nullable = false)
    private LocalDateTime slaDeadline;

    @Column(name = "warning_threshold_at", nullable = false)
    private LocalDateTime warningThresholdAt;

    @Column(name = "escalation_deadline")
    private LocalDateTime escalationDeadline;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private Status status = Status.ON_TRACK;

    @Column(name = "warning_sent_at")
    private LocalDateTime warningSentAt;

    @Column(name = "escalated_at")
    private LocalDateTime escalatedAt;

    @Column(name = "breached_at")
    private LocalDateTime breachedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    public void onUpdate() { this.updatedAt = LocalDateTime.now(); }

    public enum Status { ON_TRACK, WARNING, ESCALATED, BREACHED, COMPLETED }
}