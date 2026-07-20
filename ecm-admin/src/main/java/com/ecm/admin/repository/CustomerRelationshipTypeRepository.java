package com.ecm.admin.repository;

import com.ecm.admin.entity.CustomerRelationshipType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface CustomerRelationshipTypeRepository extends JpaRepository<CustomerRelationshipType, Integer> {
    boolean existsByName(String name);
    Optional<CustomerRelationshipType> findByName(String name);
    List<CustomerRelationshipType> findByIsActiveTrueOrderBySortOrderAscNameAsc();
}
