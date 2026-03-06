package com.ecm.workflow.controller;

import com.ecm.common.model.ApiResponse;
import com.ecm.workflow.dto.WorkflowDtos.*;
import com.ecm.workflow.service.WorkflowSlaService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/workflow/sla")
@RequiredArgsConstructor
public class WorkflowSlaController {

    private final WorkflowSlaService slaService;

    /**
     * GET /api/workflow/sla/summary
     * Returns count per SLA status for dashboard cards.
     */
    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('ECM_ADMIN','ECM_BACKOFFICE','ECM_REVIEWER')")
    public ResponseEntity<ApiResponse<SlaSummaryDto>> summary() {
        return ResponseEntity.ok(ApiResponse.ok(slaService.getSummary()));
    }

    /**
     * GET /api/workflow/sla/overdue
     * Returns active (non-completed) SLA items ordered by deadline.
     */
    @GetMapping("/overdue")
    @PreAuthorize("hasAnyRole('ECM_ADMIN','ECM_BACKOFFICE','ECM_REVIEWER')")
    public ResponseEntity<ApiResponse<List<SlaOverdueItemDto>>> overdue() {
        return ResponseEntity.ok(ApiResponse.ok(slaService.getActiveItems()));
    }
}
