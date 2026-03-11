package com.ecm.admin.entity;

import jakarta.persistence.*;
import lombok.Getter;
import org.hibernate.annotations.Immutable;

/**
 * Read-only view of ecm_core.roles.
 *
 * Sprint G: Added is_system and is_active fields (added by V5__rbac_permissions.sql).
 */
@Entity
@Immutable
@Table(name = "roles", schema = "ecm_core")
@Getter
public class RoleView {

    @Id
    private Integer id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column
    private String description;

    /** Sprint G: System roles (ECM_ADMIN etc.) cannot be deleted or renamed */
    @Column(name = "is_system")
    private Boolean isSystem;

    /** Sprint G: Soft-delete flag */
    @Column(name = "is_active")
    private Boolean isActive;
}
