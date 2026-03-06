package com.ecm.workflow.service;

import com.ecm.workflow.dto.WorkflowDtos.*;
import com.ecm.workflow.model.entity.WorkflowSlaTracking;
import com.ecm.workflow.repository.WorkflowSlaTrackingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class WorkflowSlaService {

    private final WorkflowSlaTrackingRepository slaRepo;

    /**
     * Returns counts per status for the SLA dashboard cards.
     * Uses the existing countByStatus() JPQL query.
     */
    @Transactional(readOnly = true)
    public SlaSummaryDto getSummary() {
        long onTrack = 0, warning = 0, escalated = 0, breached = 0;
        for (Object[] row : slaRepo.countByStatus()) {
            String status = (String) row[0];
            long   count  = ((Number) row[1]).longValue();
            switch (status) {
                case "ON_TRACK"  -> onTrack   = count;
                case "WARNING"   -> warning   = count;
                case "ESCALATED" -> escalated = count;
                case "BREACHED"  -> breached  = count;
            }
        }
        return new SlaSummaryDto(onTrack, warning, escalated, breached);
    }

    /**
     * Returns active (non-completed) SLA tracking rows for the overdue table.
     * Ordered by deadline ascending so most urgent appears first.
     */
    @Transactional(readOnly = true)
    public List<SlaOverdueItemDto> getActiveItems() {
        return slaRepo.findAll().stream()
                .filter(s -> s.getStatus() != WorkflowSlaTracking.Status.COMPLETED)
                .sorted(java.util.Comparator.comparing(WorkflowSlaTracking::getSlaDeadline))
                .map(this::toDto)
                .toList();
    }

    private SlaOverdueItemDto toDto(WorkflowSlaTracking s) {
        String templateName = s.getTemplate() != null ? s.getTemplate().getName() : null;
        String groupKey = s.getTemplate() != null && s.getTemplate().getEscalationGroupKey() != null
                ? s.getTemplate().getEscalationGroupKey() : null;
        return new SlaOverdueItemDto(
                s.getId(),
                s.getWorkflowInstanceId(),
                templateName,
                s.getStatus().name(),
                s.getSlaDeadline(),
                s.getEscalationDeadline(),
                groupKey);
    }
}
