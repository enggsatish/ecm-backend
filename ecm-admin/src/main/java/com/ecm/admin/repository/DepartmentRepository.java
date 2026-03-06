package com.ecm.admin.repository;

import com.ecm.admin.entity.Department;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface DepartmentRepository extends JpaRepository<Department, Integer> {
    boolean existsByCode(String code);
    Optional<Department> findByCode(String code);
    List<Department> findByIsActiveTrueOrderByNameAsc();
    List<Department> findByParentIsNullAndIsActiveTrueOrderByNameAsc();
}
