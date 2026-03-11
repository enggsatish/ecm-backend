package com.ecm.admin.entity;

import jakarta.persistence.*;
import lombok.Getter;
import org.hibernate.annotations.Immutable;

import java.io.Serializable;
import java.util.Objects;

/**
 * Read-only JPA entity for ecm_core.bundle_permissions.
 */
@Entity
@Immutable
@Table(name = "bundle_permissions", schema = "ecm_core")
@Getter
@IdClass(BundlePermissionViewId.class)
public class BundlePermissionView {

    @Id
    @Column(name = "bundle_id")
    private Integer bundleId;

    @Id
    @Column(name = "permission_id")
    private Integer permissionId;
}
