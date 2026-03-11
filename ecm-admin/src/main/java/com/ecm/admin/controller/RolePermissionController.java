package com.ecm.admin.controller;

import com.ecm.admin.dto.BundleDto;
import com.ecm.admin.dto.PermissionDto;
import com.ecm.admin.dto.RoleDto;
import com.ecm.admin.service.RolePermissionService;
import com.ecm.common.model.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * RBAC management endpoints.
 *
 * All endpoints require ECM_ADMIN role at minimum.
 * Mutation endpoints additionally require the admin:roles permission.
 *
 * Role of this controller:
 *   - Expose the role/permission/bundle data for the admin RolesPage UI
 *   - Provide CRUD for custom roles
 *   - Manage permission assignments per role
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class RolePermissionController {

    private final RolePermissionService rolePermissionService;

    // ── Roles ─────────────────────────────────────────────────────────────────

    /** List all roles with permission count and user count */
    @GetMapping("/roles")
    @PreAuthorize("hasRole('ECM_ADMIN')")
    public ResponseEntity<ApiResponse<List<RoleDto>>> listRoles() {
        return ResponseEntity.ok(ApiResponse.ok(rolePermissionService.listAllRoles()));
    }

    /** Get a single role with its full permission list */
    @GetMapping("/roles/{id}")
    @PreAuthorize("hasRole('ECM_ADMIN')")
    public ResponseEntity<ApiResponse<RoleDto>> getRole(@PathVariable Integer id) {
        return ResponseEntity.ok(ApiResponse.ok(rolePermissionService.getRole(id)));
    }

    /** Create a custom role. Name must start with ECM_ */
    @PostMapping("/roles")
    @PreAuthorize("hasAuthority('PERMISSION_admin:roles')")
    public ResponseEntity<ApiResponse<RoleDto>> createRole(
            @RequestBody RoleDto.CreateRequest req) {
        RoleDto created = rolePermissionService.createRole(req);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(created));
    }

    /** Update role name or description. System roles cannot be modified. */
    @PutMapping("/roles/{id}")
    @PreAuthorize("hasAuthority('PERMISSION_admin:roles')")
    public ResponseEntity<ApiResponse<RoleDto>> updateRole(
            @PathVariable Integer id,
            @RequestBody RoleDto.UpdateRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(rolePermissionService.updateRole(id, req)));
    }

    /**
     * Soft-delete a role (is_active = false).
     * System roles and roles with assigned users are rejected with 409.
     */
    @DeleteMapping("/roles/{id}")
    @PreAuthorize("hasAuthority('PERMISSION_admin:roles')")
    public ResponseEntity<ApiResponse<Void>> deleteRole(@PathVariable Integer id) {
        rolePermissionService.deleteRole(id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    // ── Role Permissions ──────────────────────────────────────────────────────

    /** Add a permission to a role. Cache is invalidated for all affected users. */
    @PostMapping("/roles/{id}/permissions")
    @PreAuthorize("hasAuthority('PERMISSION_admin:roles')")
    public ResponseEntity<ApiResponse<RoleDto>> addPermission(
            @PathVariable Integer id,
            @RequestBody RoleDto.AddPermissionRequest req) {
        return ResponseEntity.ok(
                ApiResponse.ok(rolePermissionService.addPermissionToRole(id, req.getPermissionCode())));
    }

    /** Remove a permission from a role. Cache is invalidated for all affected users. */
    @DeleteMapping("/roles/{id}/permissions/{code}")
    @PreAuthorize("hasAuthority('PERMISSION_admin:roles')")
    public ResponseEntity<ApiResponse<RoleDto>> removePermission(
            @PathVariable Integer id,
            @PathVariable String code) {
        return ResponseEntity.ok(
                ApiResponse.ok(rolePermissionService.removePermissionFromRole(id, code)));
    }

    /** Apply a capability bundle to a role (expands to individual permissions) */
    @PostMapping("/roles/{id}/bundles/{bundleId}")
    @PreAuthorize("hasAuthority('PERMISSION_admin:roles')")
    public ResponseEntity<ApiResponse<RoleDto>> applyBundle(
            @PathVariable Integer id,
            @PathVariable Integer bundleId) {
        return ResponseEntity.ok(
                ApiResponse.ok(rolePermissionService.applyBundleToRole(id, bundleId)));
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    /** List all 24 permission codes grouped by module */
    @GetMapping("/permissions")
    @PreAuthorize("hasRole('ECM_ADMIN')")
    public ResponseEntity<ApiResponse<List<PermissionDto>>> listPermissions() {
        return ResponseEntity.ok(ApiResponse.ok(rolePermissionService.listAllPermissions()));
    }

    // ── Bundles ───────────────────────────────────────────────────────────────

    /** List all capability bundles with their included permissions */
    @GetMapping("/bundles")
    @PreAuthorize("hasRole('ECM_ADMIN')")
    public ResponseEntity<ApiResponse<List<BundleDto>>> listBundles() {
        return ResponseEntity.ok(ApiResponse.ok(rolePermissionService.listAllBundles()));
    }
}
