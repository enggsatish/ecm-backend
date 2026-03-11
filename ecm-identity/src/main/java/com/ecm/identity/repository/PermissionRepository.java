package com.ecm.identity.repository;

import com.ecm.identity.entities.Permission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Set;

@Repository
public interface PermissionRepository extends JpaRepository<Permission, Integer> {

    /**
     * Returns all active permission codes (e.g. "documents:read") for
     * the union of roles identified by the given role IDs.
     *
     * Used by EnrichmentService and IdentityService.buildSessionDto().
     */
    @Query(value = """
            SELECT DISTINCT p.code
            FROM ecm_core.permissions p
            JOIN ecm_core.role_permissions rp ON rp.permission_id = p.id
            WHERE rp.role_id IN (:roleIds)
              AND p.is_active = true
            """, nativeQuery = true)
    Set<String> findCodesByRoleIds(@Param("roleIds") List<Integer> roleIds);
}
