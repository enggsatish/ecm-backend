package com.ecm.admin.repository;

import com.ecm.admin.entity.CustomerProfileValue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CustomerProfileValueRepository extends JpaRepository<CustomerProfileValue, Integer> {
    List<CustomerProfileValue> findByPartyId(UUID partyId);
    Optional<CustomerProfileValue> findByPartyIdAndAttribute_Id(UUID partyId, Integer attributeId);
}
