package com.ecm.admin.repository;

import com.ecm.admin.entity.BundlePermissionView;
import com.ecm.admin.entity.BundlePermissionViewId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BundlePermissionViewRepository
        extends JpaRepository<BundlePermissionView, BundlePermissionViewId> {

    @Query("SELECT bp.permissionId FROM BundlePermissionView bp WHERE bp.bundleId = :bundleId")
    List<Integer> findPermissionIdsByBundleId(@Param("bundleId") Integer bundleId);
}
