package com.ecm.workflow.repository;

import com.ecm.workflow.model.entity.WorkflowDefinitionConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface WorkflowDefinitionConfigRepository
        extends JpaRepository<WorkflowDefinitionConfig, Integer> {

    List<WorkflowDefinitionConfig> findByIsActiveTrue();

    // In WorkflowDefinitionConfigRepository.java
    Optional<WorkflowDefinitionConfig> findByProcessKey(String processKey);
}
