package com.ecm.workflow.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

/**
 * Maps a user (ecm_core.users.id) to a WorkflowGroup.
 * Cross-schema — user_id is stored as an integer without a DB-level FK.
 */
@Entity
@Table(name = "workflow_group_members", schema = "ecm_workflow",
       uniqueConstraints = @UniqueConstraint(columnNames = {"group_id", "user_id"}))
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class WorkflowGroupMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id", nullable = false)
    private WorkflowGroup group;

    /** FK to ecm_core.users.id — cross-schema, no DB constraint */
    @Column(name = "user_id", nullable = false)
    private Integer userId;

    @CreationTimestamp
    @Column(name = "added_at", updatable = false)
    private OffsetDateTime addedAt;
}
