package com.ecm.workflow.controller;

/**
 * ══════════════════════════════════════════════════════════════════════════════
 * FIX 1 — WorkflowTaskController NPE: @AuthenticationPrincipal JwtAuthenticationToken
 *
 * ROOT CAUSE (confirmed from actual source):
 *   The controller declares parameters typed as JwtAuthenticationToken:
 *
 *     @AuthenticationPrincipal JwtAuthenticationToken auth
 *
 *   @AuthenticationPrincipal resolves Authentication.getPrincipal().
 *   With spring-boot-starter-oauth2-resource-server, Spring Security stores a
 *   JwtAuthenticationToken in the SecurityContext.  getPrincipal() on that object
 *   returns the inner Jwt object — NOT the JwtAuthenticationToken itself.
 *
 *   Spring sees that Jwt ≠ JwtAuthenticationToken → injects null.
 *   Every subsequent call auth.getToken().getSubject() → NullPointerException.
 *
 *   This is why endpoints like /inbox, /claim, /approve, /reject, /request-info,
 *   /pass all throw NPE at runtime, even though the code compiles cleanly.
 *
 *   Note: @AuthenticationPrincipal Jwt jwt  (already used in /my, /unclaim,
 *   /release, /provide-info) is CORRECT because getPrincipal() IS a Jwt.
 *
 * FIX:
 *   Change the broken annotations from @AuthenticationPrincipal JwtAuthenticationToken
 *   to plain Authentication (no annotation). Spring MVC resolves unannotated
 *   Authentication parameters directly from the SecurityContext as the full
 *   Authentication object — which IS the JwtAuthenticationToken. Then cast safely.
 *
 *   Affected methods: inbox, pending, queue, claim, approve, reject,
 *                     request-info, pass.
 *
 * PATTERN (apply identically to every affected endpoint):
 *
 *   BEFORE (broken):
 *     @AuthenticationPrincipal JwtAuthenticationToken auth
 *     ...
 *     auth.getToken().getSubject()          // NPE — auth is null
 *
 *   AFTER (fixed):
 *     Authentication auth                    // no annotation; Spring injects full Authentication
 *     ...
 *     jwtAuth(auth).getToken().getSubject() // safe via helper below
 * ══════════════════════════════════════════════════════════════════════════════
 */

import com.ecm.common.audit.AuditLog;
import com.ecm.common.model.ApiResponse;
import com.ecm.workflow.dto.WorkflowDtos.*;
import com.ecm.workflow.service.EcmTaskService;
import com.ecm.workflow.service.WorkflowTaskHistoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;                          // ← ADD
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/workflow/tasks")
@RequiredArgsConstructor
public class WorkflowTaskController {

    private final EcmTaskService             taskService;
    private final WorkflowTaskHistoryService historyService;

    // ── Inbox / list ─────────────────────────────────────────────────────────

    @GetMapping("/inbox")
    public ResponseEntity<ApiResponse<List<WorkflowTaskDto>>> inbox(
            Authentication auth) {                                    // ← FIXED (was @AuthenticationPrincipal JwtAuthenticationToken auth)

        return ResponseEntity.ok(ApiResponse.ok(
                taskService.getMyInbox(
                        jwtAuth(auth).getToken().getSubject(),
                        extractCandidateGroups(auth))));
    }

    @GetMapping("/pending")
    @PreAuthorize("hasAnyRole('ECM_ADMIN', 'ECM_BACKOFFICE', 'ECM_REVIEWER')")
    public ResponseEntity<ApiResponse<List<WorkflowTaskDto>>> pending(
            Authentication auth) {                                    // ← FIXED

        return ResponseEntity.ok(ApiResponse.ok(
                taskService.getPendingTasksForGroups(extractCandidateGroups(auth))));
    }

    @GetMapping("/my")
    public ResponseEntity<ApiResponse<List<WorkflowTaskDto>>> my(
            @AuthenticationPrincipal Jwt jwt) {                      // ← already correct

        return ResponseEntity.ok(ApiResponse.ok(
                taskService.getMyTasks(jwt.getSubject())));
    }

    @GetMapping("/queue")
    @PreAuthorize("hasAnyRole('ECM_ADMIN', 'ECM_BACKOFFICE', 'ECM_REVIEWER')")
    public ResponseEntity<ApiResponse<List<TaskQueueItemDto>>> getQueue(
            @RequestParam(defaultValue = "false") boolean assignedToMe,
            Authentication auth) {                                    // ← FIXED

        if (auth == null) {
            return ResponseEntity.status(401)
                    .body(ApiResponse.error("Unauthorized for /queue", "UNAUTHORIZED"));
        }

        JwtAuthenticationToken jwtAuth = jwtAuth(auth);
        String       subject = jwtAuth.getToken().getSubject();
        List<String> groups  = extractCandidateGroupsRaw(jwtAuth);

        List<TaskQueueItemDto> items = assignedToMe
                ? taskService.getMyQueueItems(subject)
                : taskService.getQueueItems(subject, groups);

        return ResponseEntity.ok(ApiResponse.ok(items));
    }

    @GetMapping("/{taskId}")
    public ResponseEntity<ApiResponse<WorkflowTaskDto>> getTask(
            @PathVariable String taskId) {

        return ResponseEntity.ok(ApiResponse.ok(taskService.getTask(taskId)));
    }

    @GetMapping("/{taskId}/history")
    public ResponseEntity<ApiResponse<List<TaskHistoryDto>>> getHistory(
            @PathVariable String taskId) {
        return ResponseEntity.ok(ApiResponse.ok(historyService.getHistoryForTask(taskId)));
    }

    // ── Claim / Unclaim / Release ─────────────────────────────────────────────

    @PostMapping("/{taskId}/claim")
    @PreAuthorize("hasAnyRole('ECM_ADMIN', 'ECM_BACKOFFICE', 'ECM_REVIEWER')")
    @AuditLog(event = "TASK_CLAIMED", resourceType = "WORKFLOW_TASK")
    public ResponseEntity<ApiResponse<WorkflowTaskDto>> claim(
            @PathVariable String taskId,
            Authentication auth) {                                    // ← FIXED

        JwtAuthenticationToken jwtAuth = jwtAuth(auth);
        WorkflowTaskDto task = taskService.claim(
                taskId,
                jwtAuth.getToken().getSubject(),
                extractCandidateGroups(auth));

        historyService.record(
                taskId,
                task.processInstanceId(),
                "CLAIMED",
                jwtAuth.getToken().getSubject(),
                jwtAuth.getToken().getClaimAsString("email"),
                null,
                parseDocumentId(task.documentId()));

        return ResponseEntity.ok(ApiResponse.ok(task, "Task claimed successfully"));
    }

    @PostMapping("/{taskId}/unclaim")
    @PreAuthorize("hasAnyRole('ECM_ADMIN', 'ECM_BACKOFFICE', 'ECM_REVIEWER')")
    public ResponseEntity<ApiResponse<Void>> unclaim(
            @PathVariable String taskId,
            @AuthenticationPrincipal Jwt jwt) {                      // ← already correct

        taskService.unclaim(taskId, jwt.getSubject());
        return ResponseEntity.ok(ApiResponse.ok(null, "Task returned to pool"));
    }

    @PostMapping("/{taskId}/release")
    @PreAuthorize("hasAnyRole('ECM_ADMIN', 'ECM_BACKOFFICE', 'ECM_REVIEWER')")
    @AuditLog(event = "TASK_RELEASED", resourceType = "WORKFLOW_TASK")
    public ResponseEntity<ApiResponse<Void>> release(
            @PathVariable String taskId,
            @RequestBody(required = false) ReleaseTaskRequest req,
            @AuthenticationPrincipal Jwt jwt) {                      // ← already correct

        WorkflowTaskDto task = taskService.getTask(taskId);
        taskService.unclaim(taskId, jwt.getSubject());

        historyService.record(
                taskId,
                task.processInstanceId(),
                "RELEASED",
                jwt.getSubject(),
                jwt.getClaimAsString("email"),
                req != null ? req.comment() : null,
                parseDocumentId(task.documentId()));

        return ResponseEntity.ok(ApiResponse.ok(null, "Task returned to queue"));
    }

    // ── Approve ───────────────────────────────────────────────────────────────

    @PostMapping("/{taskId}/approve")
    @PreAuthorize("hasAnyRole('ECM_ADMIN', 'ECM_BACKOFFICE', 'ECM_REVIEWER')")
    @AuditLog(event = "DOCUMENT_APPROVED", resourceType = "WORKFLOW_TASK", severity = "INFO")
    public ResponseEntity<ApiResponse<Void>> approve(
            @PathVariable String taskId,
            @RequestBody @Valid TaskActionRequest req,
            Authentication auth) {                                    // ← FIXED

        JwtAuthenticationToken jwtAuth = jwtAuth(auth);
        WorkflowTaskDto task = taskService.getTask(taskId);
        taskService.approve(taskId, req, jwtAuth.getToken().getSubject(),
                extractCandidateGroups(auth));

        historyService.record(
                taskId,
                task.processInstanceId(),
                "APPROVED",
                jwtAuth.getToken().getSubject(),
                jwtAuth.getToken().getClaimAsString("email"),
                req.comment(),
                parseDocumentId(task.documentId()));

        return ResponseEntity.ok(ApiResponse.ok(null, "Document approved"));
    }

    // ── Reject ────────────────────────────────────────────────────────────────

    @PostMapping("/{taskId}/reject")
    @PreAuthorize("hasAnyRole('ECM_ADMIN', 'ECM_BACKOFFICE', 'ECM_REVIEWER')")
    @AuditLog(event = "DOCUMENT_REJECTED", resourceType = "WORKFLOW_TASK", severity = "WARN")
    public ResponseEntity<ApiResponse<Void>> reject(
            @PathVariable String taskId,
            @RequestBody @Valid TaskActionRequest req,
            Authentication auth) {                                    // ← FIXED

        if (req.comment() == null || req.comment().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("Rejection reason is required", "VALIDATION_ERROR"));
        }

        JwtAuthenticationToken jwtAuth = jwtAuth(auth);
        WorkflowTaskDto task = taskService.getTask(taskId);
        taskService.reject(taskId, req, jwtAuth.getToken().getSubject(),
                extractCandidateGroups(auth));

        historyService.record(
                taskId,
                task.processInstanceId(),
                "REJECTED",
                jwtAuth.getToken().getSubject(),
                jwtAuth.getToken().getClaimAsString("email"),
                req.comment(),
                parseDocumentId(task.documentId()));

        return ResponseEntity.ok(ApiResponse.ok(null, "Document rejected"));
    }

    // ── Request Info ──────────────────────────────────────────────────────────

    @PostMapping("/{taskId}/request-info")
    @PreAuthorize("hasAnyRole('ECM_ADMIN', 'ECM_BACKOFFICE', 'ECM_REVIEWER')")
    @AuditLog(event = "INFO_REQUESTED", resourceType = "WORKFLOW_TASK")
    public ResponseEntity<ApiResponse<Void>> requestInfo(
            @PathVariable String taskId,
            @RequestBody @Valid TaskActionRequest req,
            Authentication auth) {                                    // ← FIXED

        JwtAuthenticationToken jwtAuth = jwtAuth(auth);
        WorkflowTaskDto task = taskService.getTask(taskId);
        taskService.requestInfo(taskId, req, jwtAuth.getToken().getSubject(),
                extractCandidateGroups(auth));

        historyService.record(
                taskId,
                task.processInstanceId(),
                "INFO_REQUESTED",
                jwtAuth.getToken().getSubject(),
                jwtAuth.getToken().getClaimAsString("email"),
                req.comment(),
                parseDocumentId(task.documentId()));

        return ResponseEntity.ok(ApiResponse.ok(null,
                "Additional information requested from submitter"));
    }

    // ── Pass ──────────────────────────────────────────────────────────────────

    @PostMapping("/{taskId}/pass")
    @PreAuthorize("hasAnyRole('ECM_ADMIN', 'ECM_BACKOFFICE')")
    @AuditLog(event = "TASK_PASSED", resourceType = "WORKFLOW_TASK")
    public ResponseEntity<ApiResponse<Void>> pass(
            @PathVariable String taskId,
            @RequestBody @Valid TaskActionRequest req,
            Authentication auth) {                                    // ← FIXED

        taskService.pass(taskId, req, jwtAuth(auth).getToken().getSubject(),
                extractCandidateGroups(auth));
        return ResponseEntity.ok(ApiResponse.ok(null, "Document passed to specialist"));
    }

    // ── Provide Info ──────────────────────────────────────────────────────────

    @PostMapping("/{taskId}/provide-info")
    @AuditLog(event = "INFO_PROVIDED", resourceType = "WORKFLOW_TASK")
    public ResponseEntity<ApiResponse<WorkflowTaskDto>> provideInfo(
            @PathVariable String taskId,
            @RequestBody @Valid ProvideInfoRequest req,
            @AuthenticationPrincipal Jwt jwt) {                      // ← already correct

        WorkflowTaskDto result = taskService.provideInfo(taskId, req.comment(), jwt.getSubject());
        return ResponseEntity.ok(ApiResponse.ok(result,
                "Information provided — document returned to reviewer queue"));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Safe cast helper.
     *
     * Spring MVC injects Authentication (no annotation) as the full SecurityContext
     * Authentication, which with oauth2-resource-server is always JwtAuthenticationToken.
     * If for any reason the cast fails (e.g. different auth filter), we return 401 via
     * the RuntimeException which triggers GlobalExceptionHandler.
     */
    private JwtAuthenticationToken jwtAuth(Authentication auth) {
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            return jwtAuth;
        }
        throw new org.springframework.security.access.AccessDeniedException(
                "JWT authentication required — received: " +
                        (auth != null ? auth.getClass().getSimpleName() : "null"));
    }

    /**
     * Strips ROLE_ prefix so group names match Flowable candidateGroup values.
     * Works with any Authentication whose authorities carry ROLE_ECM_* prefixes.
     */
    private List<String> extractCandidateGroups(Authentication auth) {
        return auth.getAuthorities().stream()
                .map(a -> a.getAuthority())
                .filter(a -> a.startsWith("ROLE_ECM_"))
                .map(a -> a.replaceFirst("^ROLE_", ""))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private List<String> extractCandidateGroupsRaw(JwtAuthenticationToken auth) {
        Object groups = auth.getToken().getClaim("groups");
        if (groups instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        return extractCandidateGroups(auth);
    }

    private UUID parseDocumentId(String documentId) {
        if (documentId == null || documentId.isBlank()) return null;
        try {
            return UUID.fromString(documentId);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}