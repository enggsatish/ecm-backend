package com.ecm.admin.controller;

import com.ecm.admin.dto.DepartmentDto;
import com.ecm.admin.dto.DepartmentRequest;
import com.ecm.admin.service.DepartmentService;
import com.ecm.common.model.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/admin/departments")
public class DepartmentController {

    private final DepartmentService service;

    public DepartmentController(DepartmentService service) { this.service = service; }

    /**
     * GET /api/admin/departments
     * ?flat=false (default) → hierarchical tree
     * ?flat=true           → flat list for dropdowns
     */
    @GetMapping
    @PreAuthorize("hasRole('ECM_ADMIN')")
    public ResponseEntity<ApiResponse<List<DepartmentDto>>> list(
            @RequestParam(defaultValue = "false") boolean flat) {
        return ResponseEntity.ok(ApiResponse.ok(flat ? service.listFlat() : service.listTree()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ECM_ADMIN')")
    public ResponseEntity<ApiResponse<DepartmentDto>> getById(@PathVariable Integer id) {
        return ResponseEntity.ok(ApiResponse.ok(service.getById(id)));
    }

    @PostMapping
    @PreAuthorize("hasRole('ECM_ADMIN')")
    public ResponseEntity<ApiResponse<DepartmentDto>> create(
            @Valid @RequestBody DepartmentRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(service.create(req), "Department created"));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ECM_ADMIN')")
    public ResponseEntity<ApiResponse<DepartmentDto>> update(
            @PathVariable Integer id, @Valid @RequestBody DepartmentRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(service.update(id, req), "Department updated"));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ECM_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deactivate(@PathVariable Integer id) {
        service.deactivate(id);
        return ResponseEntity.ok(ApiResponse.ok(null, "Department deactivated"));
    }
}
