package com.ecm.workflow.repository;

import com.ecm.workflow.model.entity.WorkflowTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Optional;

public interface WorkflowTemplateRepository extends JpaRepository<WorkflowTemplate, Integer> {
    List<WorkflowTemplate> findByStatus(WorkflowTemplate.Status status);
    Optional<WorkflowTemplate> findByProcessKey(String processKey);
    Optional<WorkflowTemplate> findByIsDefaultTrueAndStatus(WorkflowTemplate.Status status);

    @Query("SELECT t FROM WorkflowTemplate t WHERE t.status = 'PUBLISHED' ORDER BY t.isDefault ASC, t.id ASC")
    List<WorkflowTemplate> findAllPublished();
}