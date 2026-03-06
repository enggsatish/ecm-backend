package com.ecm.workflow.controller;

import com.ecm.common.audit.AuditLog;
import com.ecm.common.model.ApiResponse;
import com.ecm.workflow.dto.WorkflowDtos.*;
import com.ecm.workflow.model.entity.WorkflowSlaTracking;
import com.ecm.workflow.repository.WorkflowSlaTrackingRepository;
import com.ecm.workflow.service.WorkflowInstanceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Workflow instance management.
 *
 * POST /api/workflow/instances          — start workflow (manual trigger)
 * GET  /api/workflow/instances          — list all (admin) or own (others)
 * GET  /api/workflow/instances/active   — active only
 * GET  /api/workflow/instances/mine     — started by current user
 * GET  /api/workflow/instances/{id}     — single instance
 * GET  /api/workflow/instances/document/{documentId} — all for a document
 * POST /api/workflow/instances/{id}/provide-info -
 * DELETE /api/workflow/instances/{id}   — cancel (admin only)
 */
@Slf4j
@RestController
@RequestMapping("/api/workflow/instances")
@RequiredArgsConstructor
public class WorkflowInstanceController {

    private final WorkflowInstanceService workflowInstanceService;

    private final WorkflowSlaTrackingRepository workflowSlaTrackingRepository;

    @PostMapping
    @AuditLog(event = "WORKFLOW_STARTED", resourceType = "WORKFLOW")
    public ResponseEntity<ApiResponse<WorkflowInstanceDto>> start(
            @RequestBody @Valid StartWorkflowRequest req,
            @AuthenticationPrincipal Jwt jwt) {

        String subject = jwt.getSubject();
        String email   = jwt.getClaimAsString("email");

        WorkflowInstanceDto instance =
                workflowInstanceService.startManual(req, subject, email);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.ok(instance, "Workflow started successfully"));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ECM_ADMIN', 'ECM_BACKOFFICE', 'ECM_REVIEWER')")
    public ResponseEntity<ApiResponse<Page<WorkflowInstanceDto>>> listAll(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {

        Page<WorkflowInstanceDto> result = workflowInstanceService.listAll(
                PageRequest.of(page, Math.min(size, 100)));
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @GetMapping("/active")
    @PreAuthorize("hasAnyRole('ECM_ADMIN', 'ECM_BACKOFFICE', 'ECM_REVIEWER')")
    public ResponseEntity<ApiResponse<Page<WorkflowInstanceDto>>> listActive(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {

        Page<WorkflowInstanceDto> result = workflowInstanceService.listActive(
                PageRequest.of(page, Math.min(size, 100)));
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @GetMapping("/mine")
    public ResponseEntity<ApiResponse<Page<WorkflowInstanceDto>>> listMine(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal Jwt jwt) {

        Page<WorkflowInstanceDto> result = workflowInstanceService.listMyInstances(
                jwt.getSubject(),
                PageRequest.of(page, Math.min(size, 50)));
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @GetMapping("/document/{documentId}")
    public ResponseEntity<ApiResponse<List<WorkflowInstanceDto>>> listByDocument(
            @PathVariable UUID documentId) {

        return ResponseEntity.ok(ApiResponse.ok(
                workflowInstanceService.listByDocument(documentId)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<WorkflowInstanceDto>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(workflowInstanceService.getById(id)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ECM_ADMIN')")
    @AuditLog(event = "WORKFLOW_CANCELLED", resourceType = "WORKFLOW", severity = "WARN")
    public ResponseEntity<ApiResponse<Void>> cancel(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt) {

        workflowInstanceService.cancel(id, jwt.getSubject());
        return ResponseEntity.ok(ApiResponse.ok(null, "Workflow cancelled"));
    }

    /**
     * Submitter responds to a REQUEST_INFO state — provides additional information
     * so the workflow can loop back to the reviewer pool.
     *
     * POST /api/workflow/instances/{id}/provide-info
     * Role: any authenticated user (service validates ownership)
     */
    @PostMapping("/{id}/provide-info")
    @AuditLog(event = "WORKFLOW_INFO_PROVIDED", resourceType = "WORKFLOW")
    public ResponseEntity<ApiResponse<WorkflowInstanceDto>> provideInfo(
            @PathVariable UUID id,
            @RequestBody @Valid ProvideInfoRequest req,
            @AuthenticationPrincipal Jwt jwt) {

        WorkflowInstanceDto instance =
                workflowInstanceService.provideInfo(id, req.comment(), jwt.getSubject());

        return ResponseEntity.ok(
                ApiResponse.ok(instance, "Information submitted — workflow returned to review queue"));
    }

    /** GET /api/workflow/sla/summary — dashboard counts */
    @GetMapping("/sla/summary")
    @PreAuthorize("hasAnyRole('ECM_ADMIN', 'ECM_BACKOFFICE')")
    public ResponseEntity<ApiResponse<Map<String, Long>>> slaSummary() {
        List<Object[]> rows = workflowSlaTrackingRepository.countByStatus();
        Map<String, Long> summary = new LinkedHashMap<>();
        summary.put("ON_TRACK", 0L);
        summary.put("WARNING", 0L);
        summary.put("ESCALATED", 0L);
        summary.put("BREACHED", 0L);
        for (Object[] row : rows) {
            summary.put(row[0].toString(), ((Number) row[1]).longValue());
        }
        return ResponseEntity.ok(ApiResponse.ok(summary));
    }

    /** GET /api/workflow/sla/overdue — paginated overdue list for dashboard table */
    @GetMapping("/sla/overdue")
    @PreAuthorize("hasAnyRole('ECM_ADMIN', 'ECM_BACKOFFICE')")
    public ResponseEntity<ApiResponse<List<WorkflowSlaTracking>>> slaOverdue() {
        List<WorkflowSlaTracking> overdue = workflowSlaTrackingRepository
                .findBreached(LocalDateTime.now().plusYears(100)); // all non-completed
        return ResponseEntity.ok(ApiResponse.ok(overdue));
    }
}
