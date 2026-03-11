package com.ecm.admin.entity;

import jakarta.persistence.*;
import lombok.Getter;
import org.hibernate.annotations.Immutable;

import java.time.OffsetDateTime;

/**
 * Read-only JPA entity for ecm_core.role_permissions.
 * Writes go through JdbcTemplate in RolePermissionService.
 */
@Entity
@Immutable
@Table(name = "role_permissions", schema = "ecm_core")
@Getter
@IdClass(RolePermissionViewId.class)
public class RolePermissionView {

    @Id
    @Column(name = "role_id")
    private Integer roleId;

    @Id
    @Column(name = "permission_id")
    private Integer permissionId;

    @Column(name = "granted_at")
    private OffsetDateTime grantedAt;

    @Column(name = "granted_by")
    private String grantedBy;
}
