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

    private final DocumentService   documentService;
    private final EcmUserRepository ecmUserRepository;

    private static final long   MAX_UPLOAD_BYTES    = 50L * 1024 * 1024; // 50 MB
    private static final String INTERNAL_HEADER     = "X-Internal-Service";

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Resolves the JWT subject to the ecm_core.users integer PK.
     *
     * Throws AccessDeniedException (→ HTTP 403) if the user has never logged into
     * ecm-identity (i.e. not yet provisioned). Fix: visit the frontend once so
     * GET /api/auth/me triggers auto-provisioning.
     */
    private Integer resolveUserId(Jwt jwt) {
        String subject = jwt.getSubject();
        return ecmUserRepository
                .findByEntraObjectId(subject)
                .map(EcmUser::getId)
                .orElseThrow(() -> {
                    log.warn("User not provisioned — must call /api/auth/me first. subject={}", subject);
                    return new org.springframework.security.access.AccessDeniedException(
                            "User account not provisioned. Please sign in to the ECM portal first.");
                });
    }

    // ── Upload ────────────────────────────────────────────────────────────────

    /**
     * Upload a document. Handles two callers:
     *
     * (A) Human via frontend — JWT present:
     *     Spring Security validates the Okta token and injects a non-null Jwt.
     *     uploadedBy   = ecm_core.users.id resolved from jwt.subject
     *     uploadedByEmail = jwt email claim
     *
     * (B) Internal service (DocumentPromotionClient from ecm-eforms) — no JWT:
     *     DocumentSecurityConfig permits the request before JWT validation runs.
     *     @AuthenticationPrincipal Jwt jwt is therefore null.
     *     uploadedBy      = null  (documents.uploaded_by column is nullable — OK)
     *     uploadedByEmail = "ecm-eforms"  (stored for audit trail visibility)
     *
     * The two paths are distinguished purely by whether jwt is null.
     * The X-Internal-Service header is also captured for logging.
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<DocumentResponse>> upload(
            @RequestPart("files") MultipartFile file,
            @RequestPart(value = "metadata", required = false)
            @Valid DocumentUploadRequest metadata,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = INTERNAL_HEADER, required = false) String internalService
    ) {
        if (file.isEmpty()) {
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error("Uploaded file is empty", "FILE_EMPTY"));
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            return ResponseEntity
                    .status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(ApiResponse.error("File exceeds the 50 MB limit", "FILE_TOO_LARGE"));
        }

        final boolean internalCall = (jwt == null);

        final Integer uploadedByUserId = internalCall
                ? null
                : resolveUserId(jwt);

        final String uploadedByEmail = internalCall
                ? (internalService != null ? internalService : "internal-service")
                : jwt.getClaimAsString("email");

        log.info("Upload: file={}, size={}, caller={}",
                file.getOriginalFilename(),
                file.getSize(),
                internalCall ? "internal:" + internalService : jwt.getSubject());

        DocumentResponse response = documentService.upload(
                file, metadata, uploadedByUserId, uploadedByEmail);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.ok(response, "Document uploaded successfully"));
    }

    // ── List ──────────────────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<ApiResponse<PagedResponse<DocumentResponse>>> list(
            @RequestParam(defaultValue = "0")  int    page,
            @RequestParam(defaultValue = "20") int    size,
            @RequestParam(required = false)    String search
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