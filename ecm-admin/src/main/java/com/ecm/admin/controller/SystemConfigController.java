package com.ecm.admin.controller;

import com.ecm.admin.dto.TenantConfigDto;
import com.ecm.admin.service.SystemConfigService;
import com.ecm.common.model.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/admin/config")
public class SystemConfigController {

    private final SystemConfigService service;

    public SystemConfigController(SystemConfigService service) { this.service = service; }

    /** All roles can read config (for white-label branding, etc.) */
    @GetMapping
    @PreAuthorize("hasAnyRole('ECM_ADMIN', 'ECM_BACKOFFICE', 'ECM_REVIEWER', 'ECM_READONLY')")
    public ResponseEntity<ApiResponse<List<TenantConfigDto>>> listAll() {
        return ResponseEntity.ok(ApiResponse.ok(service.listAll()));
    }

    @GetMapping("/{key}")
    @PreAuthorize("hasAnyRole('ECM_ADMIN', 'ECM_BACKOFFICE')")
    public ResponseEntity<ApiResponse<TenantConfigDto>> get(@PathVariable String key) {
        return ResponseEntity.ok(ApiResponse.ok(service.get(key)));
    }

    /** Upsert a single key */
    @PutMapping("/{key}")
    @PreAuthorize("hasRole('ECM_ADMIN')")
    public ResponseEntity<ApiResponse<TenantConfigDto>> upsert(
            @PathVariable String key, @Valid @RequestBody TenantConfigDto.UpdateRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(service.upsert(key, req), "Config saved"));
    }

    /** Bulk upsert — save entire settings form at once */
    @PutMapping
    @PreAuthorize("hasRole('ECM_ADMIN')")
    public ResponseEntity<ApiResponse<List<TenantConfigDto>>> bulkUpdate(
            @Valid @RequestBody TenantConfigDto.BulkUpdateRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(service.bulkUpdate(req.getConfigs()), "Config saved"));
    }
}
