package com.ecm.workflow.controller;

import com.ecm.common.audit.AuditLog;
import com.ecm.common.model.ApiResponse;
import com.ecm.workflow.model.entity.CategoryWorkflowMapping;
import com.ecm.workflow.service.CategoryWorkflowMappingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/workflow/categories/mappings")
@RequiredArgsConstructor
public class CategoryMappingController {

    private final CategoryWorkflowMappingService service;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'workflow:design')")
    public ResponseEntity<ApiResponse<List<CategoryWorkflowMapping>>> list() {
        return ResponseEntity.ok(ApiResponse.ok(service.listAll()));
    }

    @PostMapping
    @PreAuthorize("hasPermission(null, 'workflow:admin')")
    @AuditLog(event = "CATEGORY_MAPPING_CREATED", resourceType = "CATEGORY_WORKFLOW_MAPPING")
    public ResponseEntity<ApiResponse<CategoryWorkflowMapping>> create(
            @RequestBody CreateMappingRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        CategoryWorkflowMapping mapping = service.create(
                req.categoryId(), req.templateId(), jwt.getClaimAsString("email"));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(mapping, "Category mapping created"));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'workflow:admin')")
    @AuditLog(event = "CATEGORY_MAPPING_DELETED", resourceType = "CATEGORY_WORKFLOW_MAPPING")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Integer id) {
        service.delete(id);
        return ResponseEntity.ok(ApiResponse.ok(null, "Category mapping deleted"));
    }

    public record CreateMappingRequest(Integer categoryId, Integer templateId) {}
}
