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

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Case (loan application / account opening) management.
 *
 * GET    /api/admin/cases                                          list cases
 * GET    /api/admin/cases/{id}                                     get case with checklist
 * POST   /api/admin/cases                                          create case
 * PATCH  /api/admin/cases/{id}/status                              update case status
 * POST   /api/admin/cases/{id}/checklist/link                      link document to checklist item
 * POST   /api/admin/cases/{id}/checklist/{itemId}/waive            waive a checklist item
 * GET    /api/admin/cases/{id}/timeline                            case timeline events
 * POST   /api/admin/cases/{id}/checklist/{itemId}/start-workflow   start workflow for item
 * POST   /api/admin/cases/{id}/checklist/{itemId}/override-request request override
 * POST   /api/admin/cases/{id}/checklist/{itemId}/admin-bypass     admin bypass item
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/cases")
@RequiredArgsConstructor
public class CaseController {

    private final CaseService caseService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'CASE:VIEW')")
    public ResponseEntity<ApiResponse<List<CaseResponse>>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID partyId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String caseType) {
        return ResponseEntity.ok(ApiResponse.ok(caseService.list(status, partyId, search, caseType)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'CASE:VIEW')")
    public ResponseEntity<ApiResponse<CaseResponse>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(caseService.getById(id)));
    }

    @PostMapping
    @PreAuthorize("hasPermission(null, 'CASE:CREATE')")
    public ResponseEntity<ApiResponse<CaseResponse>> create(
            @Valid @RequestBody CreateCaseRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(caseService.create(req), "Case created"));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasPermission(null, 'CASE:UPDATE')")
    public ResponseEntity<ApiResponse<CaseResponse>> updateStatus(
            @PathVariable UUID id,
            @RequestBody UpdateCaseStatusRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(ApiResponse.ok(
                caseService.updateStatus(id, req, jwt.getSubject(), jwt.getClaimAsString("email"))));
    }

    @PostMapping("/{id}/checklist/link")
    @PreAuthorize("hasPermission(null, 'CASE:UPDATE')")
    public ResponseEntity<ApiResponse<CaseResponse>> linkDocument(
            @PathVariable UUID id,
            @RequestBody UploadChecklistDocumentRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(ApiResponse.ok(
                caseService.linkDocument(id, req, jwt.getSubject())));
    }

    @PostMapping("/{id}/checklist/{itemId}/waive")
    @PreAuthorize("hasPermission(null, 'CASE:UPDATE')")
    public ResponseEntity<ApiResponse<CaseResponse>> waiveItem(
            @PathVariable UUID id,
            @PathVariable Integer itemId,
            @RequestBody WaiveChecklistItemRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(ApiResponse.ok(
                caseService.waiveItem(id, itemId, req, jwt.getSubject())));
    }

    @PostMapping("/{id}/notes")
    @PreAuthorize("hasPermission(null, 'CASE:UPDATE')")
    public ResponseEntity<ApiResponse<CaseResponse>> addNote(
            @PathVariable UUID id,
            @RequestBody AddNoteRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        String author = jwt.getClaimAsString("email") != null
                ? jwt.getClaimAsString("email") : jwt.getSubject();
        return ResponseEntity.ok(ApiResponse.ok(caseService.addNote(id, req, author)));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasPermission(null, 'CASE:DELETE')")
    public ResponseEntity<ApiResponse<Void>> cancel(@PathVariable UUID id) {
        caseService.cancel(id);
        return ResponseEntity.ok(ApiResponse.ok(null, "Case cancelled"));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'CASE:DELETE')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
        caseService.delete(id);
        return ResponseEntity.ok(ApiResponse.ok(null, "Case deleted"));
    }

    // ── Timeline ──────────────────────────────────────────────────────────────

    @GetMapping("/{id}/timeline")
    @PreAuthorize("hasPermission(null, 'CASE:VIEW')")
    public ResponseEntity<ApiResponse<List<CaseTimelineEvent>>> getTimeline(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(caseService.getTimeline(id)));
    }

    // ── Workflow Bridge ───────────────────────────────────────────────────────

    @PostMapping("/{id}/checklist/{itemId}/start-workflow")
    @PreAuthorize("hasPermission(null, 'CASE:UPDATE')")
    public ResponseEntity<ApiResponse<CaseResponse>> startWorkflow(
            @PathVariable UUID id,
            @PathVariable Integer itemId,
            @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(ApiResponse.ok(
                caseService.startChecklistWorkflow(id, itemId, jwt.getSubject())));
    }

    // ── Override System ───────────────────────────────────────────────────────

    @PostMapping("/{id}/checklist/{itemId}/override-request")
    @PreAuthorize("hasPermission(null, 'CASE:UPDATE')")
    public ResponseEntity<ApiResponse<OverrideRequestResponse>> requestOverride(
            @PathVariable UUID id,
            @PathVariable Integer itemId,
            @RequestBody OverrideRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        String requestedBy = jwt.getClaimAsString("email") != null
                ? jwt.getClaimAsString("email") : jwt.getSubject();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(caseService.requestOverride(id, itemId, req, requestedBy)));
    }

    @PostMapping("/{id}/checklist/{itemId}/admin-bypass")
    @PreAuthorize("hasPermission(null, 'CASE:UPDATE')")
    public ResponseEntity<ApiResponse<CaseResponse>> adminBypass(
            @PathVariable UUID id,
            @PathVariable Integer itemId,
            @RequestBody AdminBypassRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(ApiResponse.ok(
                caseService.adminBypassItem(id, itemId, req, jwt.getSubject())));
    }

    // ── Assignment ────────────────────────────────────────────────────────────

    @PostMapping("/{id}/assign")
    @PreAuthorize("hasPermission(null, 'CASE:ASSIGN')")
    public ResponseEntity<ApiResponse<CaseResponse>> assignCase(
            @PathVariable UUID id,
            @RequestBody AssignCaseRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        String actor = jwt.getClaimAsString("email") != null
                ? jwt.getClaimAsString("email") : jwt.getSubject();
        return ResponseEntity.ok(ApiResponse.ok(caseService.assignCase(id, req, actor)));
    }

    @PostMapping("/{id}/claim")
    @PreAuthorize("hasPermission(null, 'CASE:ASSIGN')")
    public ResponseEntity<ApiResponse<CaseResponse>> claimCase(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt) {
        String email = jwt.getClaimAsString("email") != null
                ? jwt.getClaimAsString("email") : jwt.getSubject();
        String name = jwt.getClaimAsString("name") != null
                ? jwt.getClaimAsString("name") : email;
        return ResponseEntity.ok(ApiResponse.ok(caseService.claimCase(id, email, name)));
    }

    // ── Verification ──────────────────────────────────────────────────────────

    @PostMapping("/{id}/verify")
    @PreAuthorize("hasPermission(null, 'CASE:VERIFY')")
    public ResponseEntity<ApiResponse<CaseResponse>> verifyItems(
            @PathVariable UUID id,
            @RequestBody VerifyItemsRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        String actor = jwt.getClaimAsString("email") != null
                ? jwt.getClaimAsString("email") : jwt.getSubject();
        return ResponseEntity.ok(ApiResponse.ok(caseService.verifyItems(id, req, actor)));
    }

    // ── Request Additional Docs ───────────────────────────────────────────────

    @PostMapping("/{id}/request-docs")
    @PreAuthorize("hasPermission(null, 'CASE:UPDATE')")
    public ResponseEntity<ApiResponse<CaseResponse>> requestAdditionalDocs(
            @PathVariable UUID id,
            @RequestBody RequestAdditionalDocsRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        String actor = jwt.getClaimAsString("email") != null
                ? jwt.getClaimAsString("email") : jwt.getSubject();
        return ResponseEntity.ok(ApiResponse.ok(caseService.requestAdditionalDocs(id, req, actor)));
    }

    // ── External Participants ─────────────────────────────────────────────────

    @GetMapping("/{id}/participants")
    @PreAuthorize("hasPermission(null, 'CASE:VIEW')")
    public ResponseEntity<ApiResponse<List<ParticipantResponse>>> listParticipants(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(caseService.listParticipants(id)));
    }

    @PostMapping("/{id}/participants")
    @PreAuthorize("hasPermission(null, 'CASE:UPDATE')")
    public ResponseEntity<ApiResponse<ParticipantResponse>> addParticipant(
            @PathVariable UUID id,
            @RequestBody AddParticipantRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        String actor = jwt.getClaimAsString("email") != null
                ? jwt.getClaimAsString("email") : jwt.getSubject();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(caseService.addParticipant(id, req, actor)));
    }

    @DeleteMapping("/{id}/participants/{participantId}")
    @PreAuthorize("hasPermission(null, 'CASE:UPDATE')")
    public ResponseEntity<ApiResponse<Void>> removeParticipant(
            @PathVariable UUID id, @PathVariable Integer participantId,
            @AuthenticationPrincipal Jwt jwt) {
        String actor = jwt.getClaimAsString("email") != null
                ? jwt.getClaimAsString("email") : jwt.getSubject();
        caseService.removeParticipant(id, participantId, actor);
        return ResponseEntity.ok(ApiResponse.ok(null, "Participant removed"));
    }

    @PostMapping("/{id}/participants/share")
    @PreAuthorize("hasPermission(null, 'CASE:UPDATE')")
    public ResponseEntity<ApiResponse<Void>> shareDocuments(
            @PathVariable UUID id,
            @RequestBody ShareDocumentsRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        String actor = jwt.getClaimAsString("email") != null
                ? jwt.getClaimAsString("email") : jwt.getSubject();
        caseService.shareDocuments(id, req, actor);
        return ResponseEntity.ok(ApiResponse.ok(null, "Documents shared"));
    }

    @GetMapping("/{id}/external-uploads")
    @PreAuthorize("hasPermission(null, 'CASE:VIEW')")
    public ResponseEntity<ApiResponse<List<ExternalUploadDto>>> listExternalUploads(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(caseService.listExternalUploads(id)));
    }

    // ── External Access (no JWT — OTP + session token based) ────────────────

    @PostMapping("/external/{inviteToken}/request-otp")
    public ResponseEntity<ApiResponse<Void>> requestOtp(
            @PathVariable UUID inviteToken,
            jakarta.servlet.http.HttpServletRequest request) {
        String ip = resolveClientIp(request);
        String email = caseService.generateOtp(inviteToken, ip);
        return ResponseEntity.ok(ApiResponse.ok(null, "OTP sent to " + email));
    }

    @PostMapping("/external/{inviteToken}/verify-otp")
    public ResponseEntity<ApiResponse<Map<String, Object>>> verifyOtp(
            @PathVariable UUID inviteToken,
            @RequestBody OtpVerifyRequest req,
            jakarta.servlet.http.HttpServletRequest request) {
        String ip = resolveClientIp(request);
        return ResponseEntity.ok(ApiResponse.ok(
                caseService.verifyOtpAndGetCase(inviteToken, req.otp(), ip)));
    }

    @PostMapping("/external/session/upload")
    public ResponseEntity<ApiResponse<ExternalUploadDto>> externalUpload(
            @RequestHeader("X-External-Session") String sessionToken,
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
            @RequestParam(value = "description", required = false) String description,
            jakarta.servlet.http.HttpServletRequest request) {
        String ip = resolveClientIp(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(caseService.externalUpload(sessionToken, ip, file, description)));
    }

    @PostMapping("/external/session/comment")
    public ResponseEntity<ApiResponse<Void>> externalComment(
            @RequestHeader("X-External-Session") String sessionToken,
            @RequestBody Map<String, String> body,
            jakarta.servlet.http.HttpServletRequest request) {
        String ip = resolveClientIp(request);
        caseService.addExternalComment(sessionToken, ip, body.get("comment"));
        return ResponseEntity.ok(ApiResponse.ok(null, "Comment added"));
    }

    /** Resolve real client IP — checks X-Forwarded-For (set by gateway) before falling back to remoteAddr */
    private String resolveClientIp(jakarta.servlet.http.HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            // X-Forwarded-For can be "client, proxy1, proxy2" — take the first
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
