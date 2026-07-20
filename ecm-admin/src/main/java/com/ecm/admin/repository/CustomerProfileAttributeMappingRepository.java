package com.ecm.admin.repository;

import com.ecm.admin.entity.CustomerProfileAttributeMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface CustomerProfileAttributeMappingRepository extends JpaRepository<CustomerProfileAttributeMapping, Integer> {
    Optional<CustomerProfileAttributeMapping> findByAttribute_Id(Integer attributeId);
    void deleteByAttribute_Id(Integer attributeId);
}
