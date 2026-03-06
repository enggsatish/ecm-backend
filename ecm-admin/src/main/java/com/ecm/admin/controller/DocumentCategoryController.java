package com.ecm.admin.controller;

import com.ecm.admin.dto.CategoryDto;
import com.ecm.admin.service.DocumentCategoryService;
import com.ecm.common.model.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/admin/categories")
public class DocumentCategoryController {

    private final DocumentCategoryService service;

    public DocumentCategoryController(DocumentCategoryService service) { this.service = service; }

    @GetMapping
    @PreAuthorize("hasAnyRole('ECM_ADMIN', 'ECM_BACKOFFICE', 'ECM_REVIEWER')")
    public ResponseEntity<ApiResponse<List<CategoryDto>>> list(
            @RequestParam(defaultValue = "false") boolean flat) {
        return ResponseEntity.ok(ApiResponse.ok(flat ? service.listFlat() : service.listTree()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ECM_ADMIN', 'ECM_BACKOFFICE')")
    public ResponseEntity<ApiResponse<CategoryDto>> getById(@PathVariable Integer id) {
        return ResponseEntity.ok(ApiResponse.ok(service.getById(id)));
    }

    @PostMapping
    @PreAuthorize("hasRole('ECM_ADMIN')")
    public ResponseEntity<ApiResponse<CategoryDto>> create(
            @Valid @RequestBody CategoryDto.Request req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(service.create(req), "Category created"));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ECM_ADMIN')")
    public ResponseEntity<ApiResponse<CategoryDto>> update(
            @PathVariable Integer id, @Valid @RequestBody CategoryDto.Request req) {
        return ResponseEntity.ok(ApiResponse.ok(service.update(id, req), "Category updated"));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ECM_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deactivate(@PathVariable Integer id) {
        service.deactivate(id);
        return ResponseEntity.ok(ApiResponse.ok(null, "Category deactivated"));
    }
}
