package com.ecm.admin.controller;

import com.ecm.admin.dto.CaseDto.*;
import com.ecm.admin.service.CaseService;
import com.ecm.common.model.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Override request management — standalone endpoint for admin review panel.
 *
 * GET  /api/admin/override-requests             list all (optionally filtered by caseId)
 * POST /api/admin/override-requests/{id}/review  approve or deny
 */
@RestController
@RequestMapping("/api/admin/override-requests")
@RequiredArgsConstructor
public class OverrideRequestController {

    private final CaseService caseService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'CASE:VIEW')")
    public ResponseEntity<ApiResponse<List<OverrideRequestResponse>>> list(
            @RequestParam(required = false) UUID caseId) {
        return ResponseEntity.ok(ApiResponse.ok(caseService.listOverrideRequests(caseId)));
    }

    @PostMapping("/{id}/review")
    @PreAuthorize("hasPermission(null, 'CASE:UPDATE')")
    public ResponseEntity<ApiResponse<OverrideRequestResponse>> review(
            @PathVariable Integer id,
            @RequestBody ReviewOverrideRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        String reviewedBy = jwt.getClaimAsString("email") != null
                ? jwt.getClaimAsString("email") : jwt.getSubject();
        return ResponseEntity.ok(ApiResponse.ok(
                caseService.reviewOverrideRequest(id, req, reviewedBy)));
    }
}
