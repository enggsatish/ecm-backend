package com.ecm.admin.entity;

import jakarta.persistence.*;
import lombok.Getter;
import org.hibernate.annotations.Immutable;

/**
 * Read-only JPA entity for ecm_core.permissions.
 * Used by RolePermissionService for reading permission data.
 * All writes go through JdbcTemplate (cross-schema write rule).
 */
@Entity
@Immutable
@Table(name = "permissions", schema = "ecm_core")
@Getter
public class PermissionView {

    @Id
    private Integer id;

    @Column(name = "module_code")
    private String moduleCode;

    private String action;

    private String code;

    private String description;

    @Column(name = "is_active")
    private Boolean isActive;
}
