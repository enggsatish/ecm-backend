package com.ecm.admin.controller;

import com.ecm.admin.dto.RetentionPolicyDto;
import com.ecm.admin.service.RetentionPolicyService;
import com.ecm.common.model.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/admin/retention-policies")
@PreAuthorize("hasRole('ECM_ADMIN')")
public class RetentionPolicyController {

    private final RetentionPolicyService service;

    public RetentionPolicyController(RetentionPolicyService service) { this.service = service; }

    @GetMapping
    public ResponseEntity<ApiResponse<List<RetentionPolicyDto>>> list() {
        return ResponseEntity.ok(ApiResponse.ok(service.listActive()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<RetentionPolicyDto>> getById(@PathVariable Integer id) {
        return ResponseEntity.ok(ApiResponse.ok(service.getById(id)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<RetentionPolicyDto>> create(
            @Valid @RequestBody RetentionPolicyDto.Request req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(service.create(req), "Retention policy created"));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<RetentionPolicyDto>> update(
            @PathVariable Integer id, @Valid @RequestBody RetentionPolicyDto.Request req) {
        return ResponseEntity.ok(ApiResponse.ok(service.update(id, req), "Retention policy updated"));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deactivate(@PathVariable Integer id) {
        service.deactivate(id);
        return ResponseEntity.ok(ApiResponse.ok(null, "Retention policy deactivated"));
    }
}
