package com.ecm.workflow.controller;

import com.ecm.common.audit.AuditLog;
import com.ecm.common.model.ApiResponse;
import com.ecm.workflow.dto.WorkflowDtos.*;
import com.ecm.workflow.service.WorkflowAdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Workflow configuration endpoints (admin only, except GET /definitions).
 *
 * Base path: /api/workflow
 *
 * GET    /definitions                      list active workflow types (any auth — for start dropdown)
 * POST   /definitions                      create workflow type (ADMIN)
 * PUT    /definitions/{id}                 update (ADMIN)
 *
 * GET    /groups                           list groups (ADMIN)
 * POST   /groups                           create group (ADMIN)
 * POST   /groups/{id}/members              add user to group (ADMIN)
 * DELETE /groups/{id}/members/{userId}     remove user (ADMIN)
 *
 * GET    /categories/mappings              list category→workflow mappings (ADMIN)
 * POST   /categories/mappings              create mapping (ADMIN)
 * DELETE /categories/mappings/{id}         remove mapping (ADMIN)
 *
 * All write operations delegate to WorkflowAdminService — no repository calls here.
 */
@Slf4j
@RestController
@RequestMapping("/api/workflow")
@RequiredArgsConstructor
public class WorkflowDefinitionController {

    private final WorkflowAdminService adminService;

    // ── Workflow Definitions ──────────────────────────────────────────────────

    /** Public read — any authenticated user needs this for the "Start Workflow" dropdown */
    @GetMapping("/definitions")
    public ResponseEntity<ApiResponse<List<WorkflowDefinitionDto>>> listDefinitions() {
        return ResponseEntity.ok(ApiResponse.ok(adminService.listActiveDefinitions()));
    }

    @PostMapping("/definitions")
    @PreAuthorize("hasRole('ECM_ADMIN')")
    @AuditLog(event = "WORKFLOW_DEFINITION_CREATED", resourceType = "WORKFLOW_CONFIG")
    public ResponseEntity<ApiResponse<WorkflowDefinitionDto>> createDefinition(
            @Valid @RequestBody WorkflowDefinitionRequest req) {

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(adminService.createDefinition(req), "Workflow definition created"));
    }

    @PutMapping("/definitions/{id}")
    @PreAuthorize("hasRole('ECM_ADMIN')")
    @AuditLog(event = "WORKFLOW_DEFINITION_UPDATED", resourceType = "WORKFLOW_CONFIG")
    public ResponseEntity<ApiResponse<WorkflowDefinitionDto>> updateDefinition(
            @PathVariable Integer id,
            @Valid @RequestBody WorkflowDefinitionRequest req) {

        return ResponseEntity.ok(ApiResponse.ok(adminService.updateDefinition(id, req), "Updated"));
    }

    // ── Workflow Groups ───────────────────────────────────────────────────────

    @GetMapping("/groups")
    @PreAuthorize("hasRole('ECM_ADMIN')")
    public ResponseEntity<ApiResponse<List<WorkflowGroupDto>>> listGroups() {
        return ResponseEntity.ok(ApiResponse.ok(adminService.listGroups()));
    }

    @PostMapping("/groups")
    @PreAuthorize("hasRole('ECM_ADMIN')")
    @AuditLog(event = "WORKFLOW_GROUP_CREATED", resourceType = "WORKFLOW_CONFIG")
    public ResponseEntity<ApiResponse<WorkflowGroupDto>> createGroup(
            @Valid @RequestBody WorkflowGroupRequest req) {

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(adminService.createGroup(req), "Group created"));
    }

    @PostMapping("/groups/{groupId}/members")
    @PreAuthorize("hasRole('ECM_ADMIN')")
    @AuditLog(event = "WORKFLOW_GROUP_MEMBER_ADDED", resourceType = "WORKFLOW_CONFIG")
    public ResponseEntity<ApiResponse<Void>> addMember(
            @PathVariable Integer groupId,
            @Valid @RequestBody AddMemberRequest req) {

        adminService.addMember(groupId, req.userId());
        return ResponseEntity.ok(ApiResponse.ok(null, "Member added"));
    }

    @DeleteMapping("/groups/{groupId}/members/{userId}")
    @PreAuthorize("hasRole('ECM_ADMIN')")
    @AuditLog(event = "WORKFLOW_GROUP_MEMBER_REMOVED", resourceType = "WORKFLOW_CONFIG")
    public ResponseEntity<ApiResponse<Void>> removeMember(
            @PathVariable Integer groupId,
            @PathVariable Integer userId) {

        adminService.removeMember(groupId, userId);
        return ResponseEntity.ok(ApiResponse.ok(null, "Member removed"));
    }

    // ── Category → Workflow Mappings ──────────────────────────────────────────

    @GetMapping("/categories/mappings")
    @PreAuthorize("hasRole('ECM_ADMIN')")
    public ResponseEntity<ApiResponse<List<CategoryMappingDto>>> listCategoryMappings() {
        return ResponseEntity.ok(ApiResponse.ok(adminService.listCategoryMappings()));
    }

    @PostMapping("/categories/mappings")
    @PreAuthorize("hasRole('ECM_ADMIN')")
    @AuditLog(event = "CATEGORY_MAPPING_CREATED", resourceType = "WORKFLOW_CONFIG")
    public ResponseEntity<ApiResponse<CategoryMappingDto>> createCategoryMapping(
            @Valid @RequestBody CreateCategoryMappingRequest req) {

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(adminService.createCategoryMapping(req), "Mapping created"));
    }

    @DeleteMapping("/categories/mappings/{id}")
    @PreAuthorize("hasRole('ECM_ADMIN')")
    @AuditLog(event = "CATEGORY_MAPPING_DELETED", resourceType = "WORKFLOW_CONFIG")
    public ResponseEntity<ApiResponse<Void>> deleteCategoryMapping(@PathVariable Integer id) {
        adminService.deleteCategoryMapping(id);
        return ResponseEntity.ok(ApiResponse.ok(null, "Mapping removed"));
    }
}