package com.ecm.admin.repository;

import com.ecm.admin.entity.RolePermissionView;
import com.ecm.admin.entity.RolePermissionViewId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RolePermissionViewRepository
        extends JpaRepository<RolePermissionView, RolePermissionViewId> {

    boolean existsByRoleIdAndPermissionId(Integer roleId, Integer permissionId);

    @Query("SELECT rp.permissionId FROM RolePermissionView rp WHERE rp.roleId = :roleId")
    List<Integer> findPermissionIdsByRoleId(@Param("roleId") Integer roleId);
}
