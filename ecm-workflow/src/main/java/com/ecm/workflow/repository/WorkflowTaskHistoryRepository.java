package com.ecm.workflow.repository;

import com.ecm.workflow.model.entity.WorkflowTaskHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WorkflowTaskHistoryRepository extends JpaRepository<WorkflowTaskHistory, Long> {

    List<WorkflowTaskHistory> findByTaskIdOrderByCreatedAtAsc(String taskId);

    List<WorkflowTaskHistory> findByProcessInstanceIdOrderByCreatedAtAsc(String processInstanceId);
}
