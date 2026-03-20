package com.ecm.admin.controller;

import com.ecm.admin.dto.CaseDto.*;
import com.ecm.admin.service.CaseService;
import com.ecm.common.model.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Case (loan application / account opening) management.
 *
 * GET    /api/admin/cases                         list cases
 * GET    /api/admin/cases/{id}                    get case with checklist
 * POST   /api/admin/cases                         create case (auto-populates checklist)
 * PATCH  /api/admin/cases/{id}/status             update case status
 * POST   /api/admin/cases/{id}/checklist/link     link document to checklist item
 * POST   /api/admin/cases/{id}/checklist/{itemId}/waive  waive a checklist item
 */
@RestController
@RequestMapping("/api/admin/cases")
@RequiredArgsConstructor
public class CaseController {

    private final CaseService caseService;

    @GetMapping
    @PreAuthorize("hasAnyRole('ECM_ADMIN', 'ECM_BACKOFFICE', 'ECM_REVIEWER')")
    public ResponseEntity<ApiResponse<List<CaseResponse>>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID partyId) {
        return ResponseEntity.ok(ApiResponse.ok(caseService.list(status, partyId)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ECM_ADMIN', 'ECM_BACKOFFICE', 'ECM_REVIEWER')")
    public ResponseEntity<ApiResponse<CaseResponse>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(caseService.getById(id)));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ECM_ADMIN', 'ECM_BACKOFFICE')")
    public ResponseEntity<ApiResponse<CaseResponse>> create(
            @Valid @RequestBody CreateCaseRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(caseService.create(req), "Case created"));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('ECM_ADMIN', 'ECM_BACKOFFICE')")
    public ResponseEntity<ApiResponse<CaseResponse>> updateStatus(
            @PathVariable UUID id,
            @RequestBody UpdateCaseStatusRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(caseService.updateStatus(id, req)));
    }

    @PostMapping("/{id}/checklist/link")
    @PreAuthorize("hasAnyRole('ECM_ADMIN', 'ECM_BACKOFFICE')")
    public ResponseEntity<ApiResponse<CaseResponse>> linkDocument(
            @PathVariable UUID id,
            @RequestBody UploadChecklistDocumentRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(ApiResponse.ok(
                caseService.linkDocument(id, req, jwt.getSubject())));
    }

    @PostMapping("/{id}/checklist/{itemId}/waive")
    @PreAuthorize("hasRole('ECM_ADMIN')")
    public ResponseEntity<ApiResponse<CaseResponse>> waiveItem(
            @PathVariable UUID id,
            @PathVariable Integer itemId,
            @RequestBody WaiveChecklistItemRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(ApiResponse.ok(
                caseService.waiveItem(id, itemId, req, jwt.getSubject())));
    }

    @PostMapping("/{id}/notes")
    @PreAuthorize("hasAnyRole('ECM_ADMIN', 'ECM_BACKOFFICE')")
    public ResponseEntity<ApiResponse<CaseResponse>> addNote(
            @PathVariable UUID id,
            @RequestBody AddNoteRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        String author = jwt.getClaimAsString("email") != null
                ? jwt.getClaimAsString("email") : jwt.getSubject();
        return ResponseEntity.ok(ApiResponse.ok(caseService.addNote(id, req, author)));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('ECM_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> cancel(@PathVariable UUID id) {
        caseService.cancel(id);
        return ResponseEntity.ok(ApiResponse.ok(null, "Case cancelled"));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ECM_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
        caseService.delete(id);
        return ResponseEntity.ok(ApiResponse.ok(null, "Case deleted"));
    }
}
