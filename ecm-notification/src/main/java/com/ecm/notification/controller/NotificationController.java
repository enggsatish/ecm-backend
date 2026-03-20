package com.ecm.notification.controller;

import com.ecm.common.model.ApiResponse;
import com.ecm.notification.service.NotificationService;
import com.ecm.notification.service.NotificationService.NotificationDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * In-app notification endpoints.
 *
 * GET    /api/notifications              my notifications (unread by default)
 * GET    /api/notifications/count        unread count (for badge)
 * PATCH  /api/notifications/{id}/read    mark one as read
 * POST   /api/notifications/read-all     mark all as read
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<NotificationDto>>> getMyNotifications(
            @RequestParam(defaultValue = "false") boolean all,
            @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(ApiResponse.ok(
                notificationService.getForUser(resolveRecipient(jwt), !all)));
    }

    @GetMapping("/count")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Map<String, Integer>>> getUnreadCount(
            @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(ApiResponse.ok(
                Map.of("unread", notificationService.getUnreadCount(resolveRecipient(jwt)))));
    }

    @PatchMapping("/{id}/read")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Void>> markRead(
            @PathVariable Long id,
            @AuthenticationPrincipal Jwt jwt) {
        notificationService.markRead(id, resolveRecipient(jwt));
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @PostMapping("/read-all")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Void>> markAllRead(
            @AuthenticationPrincipal Jwt jwt) {
        notificationService.markAllRead(resolveRecipient(jwt));
        return ResponseEntity.ok(ApiResponse.ok(null, "All notifications marked as read"));
    }

    private String resolveRecipient(Jwt jwt) {
        String email = jwt.getClaimAsString("email");
        return email != null ? email : jwt.getSubject();
    }
}
