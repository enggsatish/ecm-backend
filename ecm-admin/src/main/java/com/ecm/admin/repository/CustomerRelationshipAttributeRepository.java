package com.ecm.admin.repository;

import com.ecm.admin.entity.CustomerRelationshipAttribute;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface CustomerRelationshipAttributeRepository extends JpaRepository<CustomerRelationshipAttribute, Integer> {
    List<CustomerRelationshipAttribute> findByRelationshipType_IdOrderBySortOrderAsc(Integer relationshipTypeId);
}
