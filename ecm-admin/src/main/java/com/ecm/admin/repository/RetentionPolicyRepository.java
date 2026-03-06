package com.ecm.admin.repository;

import com.ecm.admin.entity.RetentionPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface RetentionPolicyRepository extends JpaRepository<RetentionPolicy, Integer> {
    List<RetentionPolicy> findByIsActiveTrueOrderByNameAsc();
    Optional<RetentionPolicy> findByCategoryIdAndIsActiveTrue(Integer categoryId);
    Optional<RetentionPolicy> findByProductCodeAndIsActiveTrue(String productCode);
}
