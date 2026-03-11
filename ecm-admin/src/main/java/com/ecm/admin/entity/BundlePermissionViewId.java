// ─────────────────────────────────────────────────────────────────────────────
// FILE: com/ecm/admin/entity/BundlePermissionViewId.java
// ─────────────────────────────────────────────────────────────────────────────
package com.ecm.admin.entity;

import java.io.Serializable;
import java.util.Objects;

public class BundlePermissionViewId implements Serializable {
    private Integer bundleId;
    private Integer permissionId;

    public BundlePermissionViewId() {}

    public BundlePermissionViewId(Integer bundleId, Integer permissionId) {
        this.bundleId = bundleId;
        this.permissionId = permissionId;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof BundlePermissionViewId)) return false;
        BundlePermissionViewId that = (BundlePermissionViewId) o;
        return Objects.equals(bundleId, that.bundleId) && Objects.equals(permissionId, that.permissionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(bundleId, permissionId);
    }
}
