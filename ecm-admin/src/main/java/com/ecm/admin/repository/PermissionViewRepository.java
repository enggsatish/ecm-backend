// ─────────────────────────────────────────────────────────────────────────────
// FILE: com/ecm/admin/repository/PermissionViewRepository.java
// ─────────────────────────────────────────────────────────────────────────────
package com.ecm.admin.repository;

import com.ecm.admin.entity.PermissionView;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface PermissionViewRepository extends JpaRepository<PermissionView, Integer> {
    Optional<PermissionView> findByCode(String code);
    List<PermissionView> findAllByOrderByModuleCodeAscActionAsc();
}
