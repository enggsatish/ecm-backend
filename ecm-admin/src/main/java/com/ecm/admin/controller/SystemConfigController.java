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

    /** Admin-only: read all config (includes sensitive settings) */
    @GetMapping
    @PreAuthorize("hasPermission(null, 'admin:configure')")
    public ResponseEntity<ApiResponse<List<TenantConfigDto>>> listAll() {
        return ResponseEntity.ok(ApiResponse.ok(service.listAll()));
    }

    /**
     * Public branding endpoint — any authenticated user.
     * Returns only tenant.* and theme.* keys (logo, colors, name).
     * No sensitive config exposed.
     */
    @GetMapping("/branding")
    public ResponseEntity<ApiResponse<List<TenantConfigDto>>> getBranding() {
        List<TenantConfigDto> all = service.listAll();
        List<TenantConfigDto> branding = all.stream()
                .filter(c -> c.getKey().startsWith("tenant.") || c.getKey().startsWith("theme."))
                .toList();
        return ResponseEntity.ok(ApiResponse.ok(branding));
    }

    @GetMapping("/{key}")
    @PreAuthorize("hasPermission(null, 'admin:configure')")
    public ResponseEntity<ApiResponse<TenantConfigDto>> get(@PathVariable String key) {
        return ResponseEntity.ok(ApiResponse.ok(service.get(key)));
    }

    /** Upsert a single key */
    @PutMapping("/{key}")
    @PreAuthorize("hasPermission(null, 'admin:configure')")
    public ResponseEntity<ApiResponse<TenantConfigDto>> upsert(
            @PathVariable String key, @Valid @RequestBody TenantConfigDto.UpdateRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(service.upsert(key, req), "Config saved"));
    }

    /** Bulk upsert — save entire settings form at once */
    @PutMapping
    @PreAuthorize("hasPermission(null, 'admin:configure')")
    public ResponseEntity<ApiResponse<List<TenantConfigDto>>> bulkUpdate(
            @Valid @RequestBody TenantConfigDto.BulkUpdateRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(service.bulkUpdate(req.getConfigs()), "Config saved"));
    }

    /** Reset all settings to their default values */
    @PostMapping("/reset")
    @PreAuthorize("hasPermission(null, 'admin:configure')")
    public ResponseEntity<ApiResponse<List<TenantConfigDto>>> resetToDefaults() {
        return ResponseEntity.ok(ApiResponse.ok(service.resetToDefaults(), "Settings reset to defaults"));
    }
}
