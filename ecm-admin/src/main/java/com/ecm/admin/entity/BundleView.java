package com.ecm.admin.entity;

import jakarta.persistence.*;
import lombok.Getter;
import org.hibernate.annotations.Immutable;

/**
 * Read-only JPA entity for ecm_core.capability_bundles.
 * Bundles are UI-layer convenience groupings — not enforced at runtime.
 */
@Entity
@Immutable
@Table(name = "capability_bundles", schema = "ecm_core")
@Getter
public class BundleView {

    @Id
    private Integer id;

    private String code;

    private String name;

    private String description;

    @Column(name = "is_system")
    private Boolean isSystem;

    @Column(name = "sort_order")
    private Integer sortOrder;
}
