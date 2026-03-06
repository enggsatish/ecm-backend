package com.ecm.identity.controller;

import com.ecm.common.audit.AuditLog;
import com.ecm.common.model.ApiResponse;
import com.ecm.identity.model.dto.UserSessionDto;
import com.ecm.identity.service.IdentityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final IdentityService identityService;

    /**
     * Frontend calls this immediately after Okta login.
     * Returns user profile, roles, department.
     * Also provisions the user in ECM DB on first login.
     */
    @GetMapping("/me")
    @AuditLog(event = "USER_SESSION_RESOLVED", resourceType = "AUTH")
    public ResponseEntity<ApiResponse<UserSessionDto>> getCurrentUser(
            @AuthenticationPrincipal Jwt jwt) {

        // Sync user to DB (creates on first login, updates on subsequent)
        identityService.syncUserFromToken(jwt);

        // Build and return session
        UserSessionDto session = identityService.buildSessionDto(jwt);
        log.info("Session resolved for: {}", session.getEmail());

        return ResponseEntity.ok(
                ApiResponse.ok(session, "Session resolved successfully"));
    }

    /**
     * Simple ping — frontend uses this to check
     * if the token is still valid without full session load.
     */
    @GetMapping("/ping")
    public ResponseEntity<ApiResponse<String>> ping(
            @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(
                ApiResponse.ok(jwt.getSubject(), "Token is valid"));
    }

    /**
     * Logout endpoint.
     * Clears server-side Redis session.
     * Frontend must also redirect to Okta logout URL.
     */
    @PostMapping("/logout")
    @AuditLog(event = "USER_LOGOUT", resourceType = "AUTH")
    public ResponseEntity<ApiResponse<Void>> logout(
            @AuthenticationPrincipal Jwt jwt) {
        log.info("User logged out: {}", jwt.getSubject());
        return ResponseEntity.ok(
                ApiResponse.ok(null, "Logged out successfully"));
    }
}