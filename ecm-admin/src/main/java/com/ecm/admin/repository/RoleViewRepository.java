package com.ecm.admin.repository;

import com.ecm.admin.entity.RoleView;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface RoleViewRepository extends JpaRepository<RoleView, Integer> {
    Optional<RoleView> findByName(String name);
}
