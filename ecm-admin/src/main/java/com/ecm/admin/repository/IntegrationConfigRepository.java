package com.ecm.admin.repository;

import com.ecm.admin.entity.IntegrationConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface IntegrationConfigRepository extends JpaRepository<IntegrationConfig, Integer> {

    Optional<IntegrationConfig> findByTenantIdAndSystemKey(String tenantId, String system);
}
