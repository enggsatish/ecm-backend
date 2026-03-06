package com.ecm.document.controller;

import com.ecm.common.model.ApiResponse;
import com.ecm.document.dto.DocumentResponse;
import com.ecm.document.dto.DocumentUploadRequest;
import com.ecm.document.dto.PagedResponse;
import com.ecm.document.model.EcmUser;
import com.ecm.document.repository.EcmUserRepository;
import com.ecm.document.service.DocumentService;
import com.ecm.document.storage.StorageObject;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
@Slf4j
public class DocumentController {

    private final DocumentService    documentService;
    private final EcmUserRepository  ecmUserRepository;
    private static final long MAX_UPLOAD_BYTES = 50L * 1024 * 1024; // 50 MB

    // AFTER
    /**
     * Resolves the JWT subject → ecm_core.users integer PK.
     *
     * Throws AccessDeniedException (→ HTTP 403) if the user has never logged into
     * ecm-identity. This means the user exists in Okta but has not been provisioned
     * in the ECM database yet. The fix is for the user to visit the frontend once
     * (which calls GET /api/auth/me and triggers auto-provisioning).
     */
    private Integer resolveUserId(Jwt jwt) {
        String subject = jwt.getSubject();
        return ecmUserRepository
                .findByEntraObjectId(subject)
                .map(u -> u.getId())
                .orElseThrow(() -> {
                    log.warn("User not provisioned in ECM — must call /api/auth/me first. subject={}", subject);
                    return new org.springframework.security.access.AccessDeniedException(
                            "User account not provisioned. Please sign in to the ECM portal first.");
                });
    }

    // ── Upload ────────────────────────────────────────────────────────────────

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<DocumentResponse>> upload(
            @RequestPart("files") MultipartFile file,                          // "file" not "files"
            @RequestPart(value = "metadata", required = false)
            @Valid DocumentUploadRequest metadata,
            @AuthenticationPrincipal Jwt jwt
    ) {
        if (file.isEmpty()) {
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error("Uploaded file is empty", "FILE_EMPTY"));
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            return ResponseEntity
                    .status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(ApiResponse.error(
                            "File exceeds the 50 MB limit", "FILE_TOO_LARGE"));
        }
        log.info("Upload: file={}, size={}, user={}",
                file.getOriginalFilename(), file.getSize(), jwt.getSubject());
        DocumentResponse response = documentService.upload(file, metadata, resolveUserId(jwt), jwt.getClaimAsString("email"));
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.ok(response, "Document uploaded successfully"));
    }

    // ── List ──────────────────────────────────────────────────────────────────

    // AFTER — adds optional ?search=... parameter
    @GetMapping
    public ResponseEntity<ApiResponse<PagedResponse<DocumentResponse>>> list(
            @RequestParam(defaultValue = "0")   int    page,
            @RequestParam(defaultValue = "20")  int    size,
            @RequestParam(required = false)     String search
    ) {
        int safeSize = Math.min(size, 100);
        Pageable pageable = PageRequest.of(page, safeSize,
                Sort.by(Sort.Direction.DESC, "createdAt"));
        PagedResponse<DocumentResponse> result = (search != null && !search.isBlank())
                ? documentService.search(search, pageable)
                : documentService.listAll(pageable);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ── Get by ID ─────────────────────────────────────────────────────────────

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<DocumentResponse>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(documentService.getById(id)));
    }

    // ── Download ──────────────────────────────────────────────────────────────

    @GetMapping("/{id}/download")
    public ResponseEntity<InputStreamResource> download(@PathVariable UUID id) {
        DocumentResponse meta   = documentService.getById(id);
        StorageObject    object = documentService.download(id);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(
                ContentDisposition.attachment()
                        .filename(meta.originalFilename())
                        .build());
        headers.setContentLength(object.contentLength());

        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.parseMediaType(object.contentType()))
                .body(new InputStreamResource(object.content()));
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt
    ) {
        log.info("Delete: id={}, by={}", id, jwt.getSubject());
        documentService.delete(id, resolveUserId(jwt));
        return ResponseEntity.ok(ApiResponse.ok(null, "Document deleted"));
    }
}