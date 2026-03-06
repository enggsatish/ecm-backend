package com.ecm.workflow.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Named group of users assigned to handle specific document types or product lines.
 * e.g. "Underwriting Team", "Compliance Team", "Alberta Mortgages Backoffice"
 *
 * group_key is the string passed to Flowable as the candidateGroup.
 * Convention: "group:<id>" — distinct from role names like "ECM_BACKOFFICE".
 */
@Entity
@Table(name = "workflow_groups", schema = "ecm_workflow")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class WorkflowGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 500)
    private String description;

    /** Flowable candidateGroup key. Computed on create: "group:" + id */
    @Column(name = "group_key", nullable = false, unique = true, length = 100)
    private String groupKey;

    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    @OneToMany(mappedBy = "group", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<WorkflowGroupMember> members = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
