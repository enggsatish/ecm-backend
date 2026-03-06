package com.ecm.workflow.repository;

import com.ecm.workflow.model.entity.WorkflowGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface WorkflowGroupRepository extends JpaRepository<WorkflowGroup, Integer> {
    Optional<WorkflowGroup> findByGroupKey(String groupKey);
}
