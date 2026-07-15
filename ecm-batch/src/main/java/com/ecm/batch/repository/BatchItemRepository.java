package com.ecm.batch.repository;

import com.ecm.batch.entity.BatchItem;
import com.ecm.batch.entity.BatchItemStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface BatchItemRepository extends JpaRepository<BatchItem, UUID> {

    Page<BatchItem> findByBatchJobId(UUID batchId, Pageable pageable);

    List<BatchItem> findByStatus(BatchItemStatus status);

    Page<BatchItem> findByStatus(BatchItemStatus status, Pageable pageable);

    List<BatchItem> findByBatchJobIdAndStatus(UUID batchId, BatchItemStatus status);

    long countByBatchJobIdAndStatus(UUID batchId, BatchItemStatus status);

    boolean existsByDocumentId(UUID documentId);
}
