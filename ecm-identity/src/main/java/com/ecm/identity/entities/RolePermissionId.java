package com.ecm.identity.entities;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite primary key class for RolePermission entity.
 */
public class RolePermissionId implements Serializable {

    private Integer roleId;
    private Integer permissionId;

    public RolePermissionId() {}

    public RolePermissionId(Integer roleId, Integer permissionId) {
        this.roleId = roleId;
        this.permissionId = permissionId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RolePermissionId)) return false;
        RolePermissionId that = (RolePermissionId) o;
        return Objects.equals(roleId, that.roleId) &&
               Objects.equals(permissionId, that.permissionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(roleId, permissionId);
    }
}
