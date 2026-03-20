package com.ecm.notification.controller;

import com.ecm.common.model.ApiResponse;
import com.ecm.notification.service.PreferenceService;
import com.ecm.notification.service.PreferenceService.PreferenceDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * GET    /api/notifications/preferences           my preferences
 * POST   /api/notifications/preferences           set a preference
 */
@RestController
@RequestMapping("/api/notifications/preferences")
@RequiredArgsConstructor
public class PreferenceController {

    private final PreferenceService preferenceService;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<PreferenceDto>>> getMyPreferences(
            @AuthenticationPrincipal Jwt jwt) {
        String email = jwt.getClaimAsString("email") != null ? jwt.getClaimAsString("email") : jwt.getSubject();
        return ResponseEntity.ok(ApiResponse.ok(preferenceService.getForUser(email)));
    }

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Void>> setPreference(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal Jwt jwt) {
        String email = jwt.getClaimAsString("email") != null ? jwt.getClaimAsString("email") : jwt.getSubject();
        String category = (String) body.get("category");
        String channel = (String) body.get("channel");
        Boolean enabled = (Boolean) body.get("enabled");
        preferenceService.setPreference(email, category, channel, enabled != null ? enabled : true);
        return ResponseEntity.ok(ApiResponse.ok(null, "Preference updated"));
    }
}
