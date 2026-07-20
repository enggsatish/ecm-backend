package com.ecm.admin.repository;

import com.ecm.admin.entity.CustomerProfileAttribute;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface CustomerProfileAttributeRepository extends JpaRepository<CustomerProfileAttribute, Integer> {
    boolean existsByKey(String key);
    Optional<CustomerProfileAttribute> findByKey(String key);
    List<CustomerProfileAttribute> findByIsActiveTrueOrderBySortOrderAscLabelAsc();
}
