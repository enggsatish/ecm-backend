package com.ecm.workflow.repository;

import com.ecm.workflow.model.entity.WorkflowInstanceRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkflowInstanceRecordRepository
        extends JpaRepository<WorkflowInstanceRecord, UUID> {

    Optional<WorkflowInstanceRecord> findByProcessInstanceId(String processInstanceId);

    List<WorkflowInstanceRecord> findByDocumentId(UUID documentId);

    Page<WorkflowInstanceRecord> findByStatusOrderByCreatedAtDesc(
            WorkflowInstanceRecord.Status status, Pageable pageable);

    Page<WorkflowInstanceRecord> findByStartedBySubjectOrderByCreatedAtDesc(
            String startedBySubject, Pageable pageable);

    Page<WorkflowInstanceRecord> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
