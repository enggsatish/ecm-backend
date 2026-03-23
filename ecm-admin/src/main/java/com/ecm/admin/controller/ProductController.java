package com.ecm.admin.controller;

import com.ecm.admin.dto.ProductDto;
import com.ecm.admin.service.ProductService;
import com.ecm.admin.service.WorkflowClient;
import com.ecm.common.model.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/admin/products")
public class ProductController {

    private final ProductService service;
    private final WorkflowClient workflowClient;

    public ProductController(ProductService service, WorkflowClient workflowClient) {
        this.service = service;
        this.workflowClient = workflowClient;
    }

    @GetMapping
    @PreAuthorize("hasPermission(null, 'PRODUCT:VIEW')")
    public ResponseEntity<ApiResponse<Page<ProductDto>>> list(
            @RequestParam(required = false) Boolean isActive,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(service.list(isActive, PageRequest.of(page, size))));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'PRODUCT:VIEW')")
    public ResponseEntity<ApiResponse<ProductDto>> getById(@PathVariable Integer id) {
        return ResponseEntity.ok(ApiResponse.ok(service.getById(id)));
    }

    @PostMapping
    @PreAuthorize("hasPermission(null, 'PRODUCT:UPDATE')")
    public ResponseEntity<ApiResponse<ProductDto>> create(
            @Valid @RequestBody ProductDto.Request req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(service.create(req), "Product created"));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'PRODUCT:UPDATE')")
    public ResponseEntity<ApiResponse<ProductDto>> update(
            @PathVariable Integer id, @Valid @RequestBody ProductDto.Request req) {
        return ResponseEntity.ok(ApiResponse.ok(service.update(id, req), "Product updated"));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'PRODUCT:UPDATE')")
    public ResponseEntity<ApiResponse<Void>> deactivate(@PathVariable Integer id) {
        service.deactivate(id);
        return ResponseEntity.ok(ApiResponse.ok(null, "Product deactivated"));
    }

    // ── Document Types ──────────────────────────────────────────────────────

    @PostMapping("/{id}/document-types")
    @PreAuthorize("hasPermission(null, 'PRODUCT:UPDATE')")
    public ResponseEntity<ApiResponse<ProductDto>> addDocumentType(
            @PathVariable Integer id,
            @RequestBody ProductDto.DocumentTypeRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(service.addDocumentType(id, req), "Document type added"));
    }

    @DeleteMapping("/{id}/document-types/{docTypeId}")
    @PreAuthorize("hasPermission(null, 'PRODUCT:UPDATE')")
    public ResponseEntity<ApiResponse<Void>> removeDocumentType(
            @PathVariable Integer id, @PathVariable Integer docTypeId) {
        service.removeDocumentType(id, docTypeId);
        return ResponseEntity.ok(ApiResponse.ok(null, "Document type removed"));
    }

    /** Proxy to ecm-workflow — returns available workflow definitions for admin dropdowns */
    @GetMapping("/workflow-definitions")
    @PreAuthorize("hasPermission(null, 'PRODUCT:UPDATE')")
    public ResponseEntity<ApiResponse<List<WorkflowClient.WorkflowDefinitionSummary>>> getWorkflowDefs() {
        return ResponseEntity.ok(ApiResponse.ok(workflowClient.getDefinitions()));
    }
}
