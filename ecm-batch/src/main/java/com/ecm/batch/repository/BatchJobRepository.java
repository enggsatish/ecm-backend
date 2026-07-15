package com.ecm.batch.repository;

import com.ecm.batch.entity.BatchJob;
import com.ecm.batch.entity.BatchJobStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface BatchJobRepository extends JpaRepository<BatchJob, UUID> {

    List<BatchJob> findByStatusIn(Collection<BatchJobStatus> statuses);

    Page<BatchJob> findByCreatedByOrderByCreatedAtDesc(String createdBy, Pageable pageable);

    Page<BatchJob> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
