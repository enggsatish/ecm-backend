package com.ecm.admin.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.OffsetDateTime;

/**
 * Second level of financial hierarchy, owned by a Segment.
 * Maps to ecm_admin.product_lines (created by V2__add_hierarchy.sql).
 */
@Entity
@Table(schema = "ecm_admin", name = "product_lines")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    /** Soft FK → ecm_admin.segments.id — enforced by DB constraint within same schema. */
    @Column(name = "segment_id", nullable = false)
    private Integer segmentId;

    @Column(nullable = false, length = 100)
    private String name;

    /** Unique composite code, e.g. RETAIL_LOANS, COMM_BANKING. */
    @Column(nullable = false, length = 30, unique = true)
    private String code;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}