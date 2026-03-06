package com.ecm.workflow.controller;

import com.ecm.common.audit.AuditLog;
import com.ecm.common.model.ApiResponse;
import com.ecm.workflow.dto.WorkflowDtos.*;
import com.ecm.workflow.service.EcmTaskService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

import java.util.List;
/* * Task inbox and action endpoints.
 *
 * GET  /api/workflow/tasks/inbox      — full inbox (claimed + available pool)
 * GET  /api/workflow/tasks/pending    — unclaimed pool tasks for my groups
 * GET  /api/workflow/tasks/my         — tasks I have claimed
 * GET  /api/workflow/tasks/{id}       — single task detail
 * POST /api/workflow/tasks/{id}/claim         — claim from pool
 * POST /api/workflow/tasks/{id}/unclaim       — return to pool
 * POST /api/workflow/tasks/{id}/approve       — approve document
 * POST /api/workflow/tasks/{id}/reject        — reject document (comment required)
 * POST /api/workflow/tasks/{id}/request-info  — request more information
 * POST /api/workflow/tasks/{id}/pass          — pass to specialist (triage only)
 */
@Slf4j
@RestController
@RequestMapping("/api/workflow/tasks")
@RequiredArgsConstructor
public class WorkflowTaskController {

    private final EcmTaskService taskService;

    // ── Inbox / list ──────────────────────────────────────────────────────

    @GetMapping("/inbox")
    public ResponseEntity<ApiResponse<List<WorkflowTaskDto>>> inbox(
            @AuthenticationPrincipal JwtAuthenticationToken auth) {

        return ResponseEntity.ok(ApiResponse.ok(
                taskService.getMyInbox(
                        auth.getToken().getSubject(),
                        extractCandidateGroups(auth))));
    }

    @GetMapping("/pending")
    @PreAuthorize("hasAnyRole('ECM_ADMIN', 'ECM_BACKOFFICE', 'ECM_REVIEWER')")
    public ResponseEntity<ApiResponse<List<WorkflowTaskDto>>> pending(
            @AuthenticationPrincipal JwtAuthenticationToken auth) {

        return ResponseEntity.ok(ApiResponse.ok(
                taskService.getPendingTasksForGroups(extractCandidateGroups(auth))));
    }

    @GetMapping("/my")
    public ResponseEntity<ApiResponse<List<WorkflowTaskDto>>> my(
            @AuthenticationPrincipal Jwt jwt) {

        return ResponseEntity.ok(ApiResponse.ok(
                taskService.getMyTasks(jwt.getSubject())));
    }

    @GetMapping("/{taskId}")
    public ResponseEntity<ApiResponse<WorkflowTaskDto>> getTask(
            @PathVariable String taskId) {

        return ResponseEntity.ok(ApiResponse.ok(taskService.getTask(taskId)));
    }

    // ── Task actions ──────────────────────────────────────────────────────

    @PostMapping("/{taskId}/claim")
    @PreAuthorize("hasAnyRole('ECM_ADMIN', 'ECM_BACKOFFICE', 'ECM_REVIEWER')")
    @AuditLog(event = "TASK_CLAIMED", resourceType = "WORKFLOW_TASK")
    public ResponseEntity<ApiResponse<WorkflowTaskDto>> claim(
            @PathVariable String taskId,
            @AuthenticationPrincipal JwtAuthenticationToken auth) {

        WorkflowTaskDto task = taskService.claim(
                taskId,
                auth.getToken().getSubject(),
                extractCandidateGroups(auth));
        return ResponseEntity.ok(ApiResponse.ok(task, "Task claimed successfully"));
    }

    @PostMapping("/{taskId}/unclaim")
    @PreAuthorize("hasAnyRole('ECM_ADMIN', 'ECM_BACKOFFICE', 'ECM_REVIEWER')")
    public ResponseEntity<ApiResponse<Void>> unclaim(
            @PathVariable String taskId,
            @AuthenticationPrincipal Jwt jwt) {

        taskService.unclaim(taskId, jwt.getSubject());
        return ResponseEntity.ok(ApiResponse.ok(null, "Task returned to pool"));
    }

    @PostMapping("/{taskId}/approve")
    @PreAuthorize("hasAnyRole('ECM_ADMIN', 'ECM_BACKOFFICE', 'ECM_REVIEWER')")
    @AuditLog(event = "DOCUMENT_APPROVED", resourceType = "WORKFLOW_TASK", severity = "INFO")
    public ResponseEntity<ApiResponse<Void>> approve(
            @PathVariable String taskId,
            @RequestBody @Valid TaskActionRequest req,
            @AuthenticationPrincipal JwtAuthenticationToken auth) {

        taskService.approve(taskId, req, auth.getToken().getSubject(),
                extractCandidateGroups(auth));
        return ResponseEntity.ok(ApiResponse.ok(null, "Document approved"));
    }

    @PostMapping("/{taskId}/reject")
    @PreAuthorize("hasAnyRole('ECM_ADMIN', 'ECM_BACKOFFICE', 'ECM_REVIEWER')")
    @AuditLog(event = "DOCUMENT_REJECTED", resourceType = "WORKFLOW_TASK", severity = "WARN")
    public ResponseEntity<ApiResponse<Void>> reject(
            @PathVariable String taskId,
            @RequestBody @Valid TaskActionRequest req,
            @AuthenticationPrincipal JwtAuthenticationToken auth) {

        taskService.reject(taskId, req, auth.getToken().getSubject(),
                extractCandidateGroups(auth));
        return ResponseEntity.ok(ApiResponse.ok(null, "Document rejected"));
    }

    @PostMapping("/{taskId}/request-info")
    @PreAuthorize("hasAnyRole('ECM_ADMIN', 'ECM_BACKOFFICE', 'ECM_REVIEWER')")
    @AuditLog(event = "INFO_REQUESTED", resourceType = "WORKFLOW_TASK")
    public ResponseEntity<ApiResponse<Void>> requestInfo(
            @PathVariable String taskId,
            @RequestBody @Valid TaskActionRequest req,
            @AuthenticationPrincipal JwtAuthenticationToken auth) {

        taskService.requestInfo(taskId, req, auth.getToken().getSubject(),
                extractCandidateGroups(auth));
        return ResponseEntity.ok(ApiResponse.ok(null,
                "Additional information requested from submitter"));
    }

    @PostMapping("/{taskId}/pass")
    @PreAuthorize("hasAnyRole('ECM_ADMIN', 'ECM_BACKOFFICE')")
    @AuditLog(event = "TASK_PASSED", resourceType = "WORKFLOW_TASK")
    public ResponseEntity<ApiResponse<Void>> pass(
            @PathVariable String taskId,
            @RequestBody @Valid TaskActionRequest req,
            @AuthenticationPrincipal JwtAuthenticationToken auth) {

        taskService.pass(taskId, req, auth.getToken().getSubject(),
                extractCandidateGroups(auth));
        return ResponseEntity.ok(ApiResponse.ok(null, "Document passed to specialist"));
    }

    /**
     * Submitter provides information after a REQUEST_INFO decision.
     * Only the user assigned to the "Provide Additional Information" task
     * may call this. Visible to any authenticated user — the task-level
     * assignee check inside EcmTaskService enforces ownership.
     *
     * POST /api/workflow/tasks/{taskId}/provide-info
     * Body: { "comment": "Here is the additional information..." }
     */
    @PostMapping("/{taskId}/provide-info")
    @AuditLog(event = "INFO_PROVIDED", resourceType = "WORKFLOW_TASK")
    public ResponseEntity<ApiResponse<WorkflowTaskDto>> provideInfo(
            @PathVariable String taskId,
            @RequestBody @Valid ProvideInfoRequest req,
            @AuthenticationPrincipal Jwt jwt) {

        WorkflowTaskDto result = taskService.provideInfo(taskId, req.comment(), jwt.getSubject());
        return ResponseEntity.ok(ApiResponse.ok(result, "Information provided — document returned to reviewer queue"));
    }

    // ── Helper ────────────────────────────────────────────────────────────

    /**
     * Extract Flowable-compatible candidate group strings from the JWT.
     *
     * Okta groups arrive as ROLE_ECM_BACKOFFICE etc. after EcmJwtConverter.
     * We strip ROLE_ prefix so they match what Flowable stores as candidateGroup.
     *
     * Example: ROLE_ECM_BACKOFFICE → ECM_BACKOFFICE
     * Also includes group keys like "group:3" if they're in the JWT
     * (custom Okta claim or future enhancement).
     */
    private List<String> extractCandidateGroups(JwtAuthenticationToken auth) {
        return auth.getAuthorities().stream()
                .map(a -> a.getAuthority())
                .filter(a -> a.startsWith("ROLE_ECM_"))
                .map(a -> a.replaceFirst("^ROLE_", ""))
                .toList();
    }
}
