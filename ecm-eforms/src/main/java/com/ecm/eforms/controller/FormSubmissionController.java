package com.ecm.eforms.controller;

import com.ecm.eforms.mapper.FormMapper;
import com.ecm.common.model.ApiResponse;
import com.ecm.eforms.model.dto.EFormsDtos.*;
import com.ecm.eforms.model.entity.FormSubmission;
import com.ecm.eforms.service.FormSubmissionService;
import com.ecm.eforms.service.FormSubmissionService.FormValidationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Form submission endpoints.
 *
 * POST   /api/eforms/submissions              submit or save draft
 * GET    /api/eforms/submissions/mine         own submissions (any auth user)
 * GET    /api/eforms/submissions              all submissions (backoffice)
 * GET    /api/eforms/submissions/{id}         detail (owner or backoffice)
 * GET    /api/eforms/submissions/{id}/pdf     regenerated PDF (owner or backoffice)
 * POST   /api/eforms/submissions/{id}/signed-copy  manually-signed replacement upload
 * POST   /api/eforms/submissions/{id}/withdraw
 */
@RestController
@RequestMapping("/api/eforms/submissions")
@RequiredArgsConstructor
public class FormSubmissionController {

    private final FormSubmissionService submissionService;
    private final FormMapper            formMapper;

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<FormSubmissionDto>> submit(
            @Valid @RequestBody SubmitFormRequest req,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest http) {

        try {
            FormSubmission sub = submissionService.submit(req,
                    jwt.getSubject(),
                    jwt.getClaimAsString("name"),
                    http.getRemoteAddr(),
                    http.getHeader("User-Agent"));

            HttpStatus status = req.isDraft() ? HttpStatus.OK : HttpStatus.CREATED;
            return ResponseEntity.status(status)
                    .body(ApiResponse.ok(formMapper.toSubmissionDto(sub)));

        } catch (FormValidationException e) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(ApiResponse.error("Validation failed: " + e.getFieldErrors(), "VALIDATION_ERROR"));
        }
    }

    @GetMapping("/mine")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Page<FormSubmissionSummary>>> listMine(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {

        Page<FormSubmission> subs = submissionService.listForUser(
                jwt.getSubject(), PageRequest.of(page, size, Sort.by("createdAt").descending()));
        return ResponseEntity.ok(ApiResponse.ok(subs.map(formMapper::toSubmissionSummary)));
    }

    @GetMapping
    @PreAuthorize("hasPermission(null, 'eforms:review')")
    public ResponseEntity<ApiResponse<Page<FormSubmissionSummary>>> listAll(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String formKey,
            @RequestParam(required = false) String assignedTo,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {

        Page<FormSubmission> subs = submissionService.listAll(status, formKey, assignedTo,
                PageRequest.of(page, size, Sort.by("createdAt").descending()));
        return ResponseEntity.ok(ApiResponse.ok(subs.map(formMapper::toSubmissionSummary)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<FormSubmissionDto>> getById(
            @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {

        FormSubmission sub = submissionService.getById(id);

        // Non-privileged users may only see their own submissions
        List<String> groups = jwt.getClaimAsStringList("groups");
        boolean privileged = groups != null && groups.stream()
                .anyMatch(g -> g.contains("ADMIN") || g.contains("BACKOFFICE") || g.contains("REVIEWER"));

        if (!privileged && !sub.getSubmittedBy().equals(jwt.getSubject()))
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("Access denied", "ACCESS_DENIED"));

        return ResponseEntity.ok(ApiResponse.ok(formMapper.toSubmissionDto(sub)));
    }

    @GetMapping("/{id}/pdf")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<byte[]> getPdf(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        FormSubmission sub = submissionService.getById(id);

        List<String> groups = jwt.getClaimAsStringList("groups");
        boolean privileged = groups != null && groups.stream()
                .anyMatch(g -> g.contains("ADMIN") || g.contains("BACKOFFICE") || g.contains("REVIEWER"));

        if (!privileged && !sub.getSubmittedBy().equals(jwt.getSubject()))
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();

        byte[] pdf = submissionService.getPdf(id);
        String filename = sub.getFormKey() + "-" + sub.getId().toString().substring(0, 8) + ".pdf";

        return ResponseEntity.ok()
                .header("Content-Type", "application/pdf")
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .body(pdf);
    }

    @PostMapping("/{id}/signed-copy")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<FormSubmissionDto>> uploadSignedCopy(
            @PathVariable UUID id,
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
            @AuthenticationPrincipal Jwt jwt) {

        FormSubmission sub = submissionService.getById(id);
        List<String> groups = jwt.getClaimAsStringList("groups");
        boolean privileged = groups != null && groups.stream()
                .anyMatch(g -> g.contains("ADMIN") || g.contains("BACKOFFICE") || g.contains("REVIEWER"));

        if (!privileged && !sub.getSubmittedBy().equals(jwt.getSubject()))
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("Access denied", "ACCESS_DENIED"));

        try {
            FormSubmission updated = submissionService.uploadSignedCopy(
                    id, file.getBytes(), file.getOriginalFilename(), jwt.getSubject());
            return ResponseEntity.ok(ApiResponse.ok(formMapper.toSubmissionDto(updated)));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(ApiResponse.error(e.getMessage(), "SIGNATURE_NOT_REQUIRED"));
        } catch (java.io.IOException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error("Could not read uploaded file", "UPLOAD_FAILED"));
        }
    }

    @PostMapping("/{id}/withdraw")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<FormSubmissionDto>> withdraw(
            @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {

        FormSubmission withdrawn = submissionService.withdraw(id, jwt.getSubject());
        return ResponseEntity.ok(ApiResponse.ok(formMapper.toSubmissionDto(withdrawn)));
    }
}