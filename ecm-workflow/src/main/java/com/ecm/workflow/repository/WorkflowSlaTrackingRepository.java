package com.ecm.workflow.repository;

import com.ecm.workflow.model.entity.WorkflowSlaTracking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkflowSlaTrackingRepository extends JpaRepository<WorkflowSlaTracking, Integer> {
    Optional<WorkflowSlaTracking> findByWorkflowInstanceId(UUID instanceId);

    /** All active SLA records that have passed warning threshold but not yet warned */
    @Query("""
        SELECT s FROM WorkflowSlaTracking s
        WHERE s.status = 'ON_TRACK'
          AND s.warningThresholdAt <= :now
        """)
    List<WorkflowSlaTracking> findDueForWarning(@Param("now") LocalDateTime now);

    /** All active records past deadline */
    @Query("""
        SELECT s FROM WorkflowSlaTracking s
        WHERE s.status IN ('ON_TRACK', 'WARNING')
          AND s.slaDeadline <= :now
        """)
    List<WorkflowSlaTracking> findBreached(@Param("now") LocalDateTime now);

    /** Warned records past escalation deadline */
    @Query("""
        SELECT s FROM WorkflowSlaTracking s
        WHERE s.status = 'BREACHED'
          AND s.escalationDeadline IS NOT NULL
          AND s.escalationDeadline <= :now
          AND s.escalatedAt IS NULL
        """)
    List<WorkflowSlaTracking> findDueForEscalation(@Param("now") LocalDateTime now);

    /** Summary counts for dashboard */
    @Query("""
        SELECT s.status, COUNT(s) FROM WorkflowSlaTracking s
        WHERE s.status NOT IN ('COMPLETED')
        GROUP BY s.status
        """)
    List<Object[]> countByStatus();
}