package com.ecm.admin.controller;

import com.ecm.admin.dto.AdminUserDto;
import com.ecm.admin.service.UserAdminService;
import com.ecm.common.model.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasRole('ECM_ADMIN')")
public class UserAdminController {

    private final UserAdminService service;

    public UserAdminController(UserAdminService service) { this.service = service; }

    @GetMapping
    public ResponseEntity<ApiResponse<Page<AdminUserDto>>> search(
            @RequestParam(required = false) Boolean isActive,
            @RequestParam(required = false) Integer departmentId,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(
                service.search(isActive, departmentId, search,
                        PageRequest.of(page, size, Sort.by("email")))));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<AdminUserDto>> getById(@PathVariable Integer id) {
        return ResponseEntity.ok(ApiResponse.ok(service.getById(id)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<AdminUserDto>> update(
            @PathVariable Integer id, @Valid @RequestBody AdminUserDto.UpdateRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(service.update(id, req), "User updated"));
    }

    @PostMapping("/{id}/roles")
    public ResponseEntity<ApiResponse<AdminUserDto>> addRole(
            @PathVariable Integer id, @Valid @RequestBody AdminUserDto.RoleRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(service.addRole(id, req.getRoleName()), "Role added"));
    }

    @DeleteMapping("/{id}/roles/{roleName}")
    public ResponseEntity<ApiResponse<AdminUserDto>> removeRole(
            @PathVariable Integer id, @PathVariable String roleName) {
        return ResponseEntity.ok(ApiResponse.ok(service.removeRole(id, roleName), "Role removed"));
    }

    /**
     * Pre-register a user before their first SSO login.
     * Creates a pending users row + assigns an initial role.
     * The account activates automatically when the user first authenticates via EntraID/Okta.
     *
     * POST /api/admin/users/invite
     * Body: { email, displayName?, departmentId?, initialRole? }
     */
    @PostMapping("/invite")
    public ResponseEntity<ApiResponse<AdminUserDto>> invite(
            @Valid @RequestBody AdminUserDto.InviteRequest req) {
        return ResponseEntity.status(201)
                .body(ApiResponse.ok(service.inviteUser(req), "User invited"));
    }

    @PostMapping("/{id}/deactivate")
    public ResponseEntity<ApiResponse<Void>> deactivate(
            @PathVariable Integer id, @AuthenticationPrincipal Jwt jwt) {
        service.deactivate(id, jwt.getClaimAsString("email"));
        return ResponseEntity.ok(ApiResponse.ok(null, "User deactivated"));
    }

    @PostMapping("/{id}/reactivate")
    public ResponseEntity<ApiResponse<Void>> reactivate(@PathVariable Integer id) {
        service.reactivate(id);
        return ResponseEntity.ok(ApiResponse.ok(null, "User reactivated"));
    }
}