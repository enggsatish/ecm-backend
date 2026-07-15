package com.ecm.batch.repository;

import com.ecm.batch.entity.WatchFolderConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface WatchFolderConfigRepository extends JpaRepository<WatchFolderConfig, Integer> {

    Optional<WatchFolderConfig> findByTenantId(String tenantId);

    default Optional<WatchFolderConfig> findDefault() {
        return findByTenantId("default");
    }
}
