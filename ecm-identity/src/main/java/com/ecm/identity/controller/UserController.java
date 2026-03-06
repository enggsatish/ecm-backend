package com.ecm.identity.controller;

import com.ecm.common.audit.AuditLog;
import com.ecm.common.model.ApiResponse;
import com.ecm.identity.model.dto.UserProfileDto;
import com.ecm.identity.service.IdentityService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final IdentityService identityService;

    /**
     * Any authenticated user can view their own profile.
     */
    @GetMapping("/me/profile")
    public ResponseEntity<ApiResponse<UserProfileDto>> getMyProfile(
            @AuthenticationPrincipal Jwt jwt) {
        UserProfileDto profile =
                identityService.getUserProfile(jwt.getSubject());
        return ResponseEntity.ok(ApiResponse.ok(profile));
    }

    /**
     * Admin only — view any user's profile.
     */
    @GetMapping("/{subject}")
    @PreAuthorize("hasRole('ECM_ADMIN')")
    @AuditLog(event = "USER_PROFILE_VIEWED", resourceType = "USER")
    public ResponseEntity<ApiResponse<UserProfileDto>> getUserProfile(
            @PathVariable String subject) {
        UserProfileDto profile =
                identityService.getUserProfile(subject);
        return ResponseEntity.ok(ApiResponse.ok(profile));
    }

    /**
     * Admin only — list all active users.
     */
    @GetMapping
    @PreAuthorize("hasRole('ECM_ADMIN')")
    @AuditLog(event = "USER_LIST_VIEWED", resourceType = "USER")
    public ResponseEntity<ApiResponse<List<UserProfileDto>>> getAllUsers() {
        List<UserProfileDto> users =
                identityService.getAllActiveUsers();
        return ResponseEntity.ok(
                ApiResponse.ok(users, users.size() + " users found"));
    }

    /**
     * Admin only — deactivate a user.
     */
    @PatchMapping("/{userId}/deactivate")
    @PreAuthorize("hasRole('ECM_ADMIN')")
    @AuditLog(event = "USER_DEACTIVATED", resourceType = "USER",
            severity = "WARN")
    public ResponseEntity<ApiResponse<Void>> deactivateUser(
            @PathVariable Integer userId) {
        identityService.deactivateUser(userId);
        return ResponseEntity.ok(
                ApiResponse.ok(null, "User deactivated"));
    }
}