package com.ecm.admin.entity;

import java.io.Serializable;
import java.util.Objects;

public class RolePermissionViewId implements Serializable {
    private Integer roleId;
    private Integer permissionId;

    public RolePermissionViewId() {}

    public RolePermissionViewId(Integer roleId, Integer permissionId) {
        this.roleId = roleId;
        this.permissionId = permissionId;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof RolePermissionViewId)) return false;
        RolePermissionViewId that = (RolePermissionViewId) o;
        return Objects.equals(roleId, that.roleId) && Objects.equals(permissionId, that.permissionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(roleId, permissionId);
    }
}
