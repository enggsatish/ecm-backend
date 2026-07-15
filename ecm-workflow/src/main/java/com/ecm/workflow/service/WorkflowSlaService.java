package com.ecm.workflow.service;

import com.ecm.workflow.dto.WorkflowDtos.*;
import com.ecm.workflow.model.entity.WorkflowSlaTracking;
import com.ecm.workflow.model.entity.WorkflowSlaTracking.Status;
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
     * Simple in-memory count — safe and avoids JPQL enum casting issues.
     */
    @Transactional(readOnly = true)
    public SlaSummaryDto getSummary() {
        List<WorkflowSlaTracking> all = slaRepo.findAll();

        long onTrack   = all.stream().filter(s -> s.getStatus() == Status.ON_TRACK).count();
        long warning   = all.stream().filter(s -> s.getStatus() == Status.WARNING).count();
        long escalated = all.stream().filter(s -> s.getStatus() == Status.ESCALATED).count();
        long breached  = all.stream().filter(s -> s.getStatus() == Status.BREACHED).count();

        return new SlaSummaryDto(onTrack, warning, escalated, breached);
    }

    /**
     * Returns active (non-completed) SLA tracking rows for the overdue table.
     * Ordered by deadline ascending so most urgent appears first.
     */
    @Transactional(readOnly = true)
    public List<SlaOverdueItemDto> getActiveItems() {
        return slaRepo.findAll().stream()
                .filter(s -> s.getStatus() != Status.COMPLETED)
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
