package com.ecm.document.controller;

import com.ecm.common.model.ApiResponse;
import com.ecm.document.dto.DocumentResponse;
import com.ecm.document.dto.DocumentUploadRequest;
import com.ecm.document.entity.DocumentStatus;
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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.UUID;

import com.ecm.document.config.RabbitMqConfig;
import com.ecm.document.entity.Document;
import com.ecm.document.repository.DocumentRepository;

@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
@Slf4j
public class DocumentController {

    private final DocumentService      documentService;
    private final EcmUserRepository    ecmUserRepository;
    private final DocumentRepository   documentRepository;
    private final com.ecm.document.service.DocumentStateGuard stateGuard;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final org.springframework.amqp.rabbit.core.RabbitTemplate rabbitTemplate;

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
            @RequestParam(required = false)    String search,
            @RequestParam(required = false)    String partyExternalId,
            @RequestParam(required = false)    Boolean needsClassification,
            @RequestParam(required = false)    Boolean autoClassified
    ) {
        int safeSize = Math.min(size, 100);
        Pageable pageable = PageRequest.of(page, safeSize,
                Sort.by(Sort.Direction.DESC, "createdAt"));

        PagedResponse<DocumentResponse> result;
        if (Boolean.TRUE.equals(needsClassification)) {
            result = documentService.listNeedsClassification(pageable);
        } else if (Boolean.TRUE.equals(autoClassified)) {
            result = documentService.listAutoClassified(pageable);
        } else if (partyExternalId != null && !partyExternalId.isBlank()) {
            result = documentService.listByParty(partyExternalId.trim(), pageable);
        } else if (search != null && !search.isBlank()) {
            result = documentService.search(search, pageable);
        } else {
            result = documentService.listAll(pageable);
        }

        // Enrich with case linkage info (batch query for all docs in this page)
        result = enrichWithCaseInfo(result);

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

    // ── Soft Delete (admin only, with reason) ──────────────────────────────────

    @DeleteMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'DOCUMENT:DELETE')")
    public ResponseEntity<ApiResponse<Void>> softDelete(
            @PathVariable UUID id,
            @RequestParam(required = false) String reason,
            @AuthenticationPrincipal Jwt jwt
    ) {
        log.info("Soft delete: id={}, reason='{}', by={}", id, reason, jwt.getSubject());
        documentService.softDelete(id, reason != null ? reason : "No reason provided",
                jwt.getClaimAsString("email"));
        return ResponseEntity.ok(ApiResponse.ok(null, "Document deleted"));
    }

    // ── Archive ──────────────────────────────────────────────────────────────

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasPermission(null, 'archive:manage')")
    public ResponseEntity<ApiResponse<Void>> archive(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt
    ) {
        documentService.archive(id, jwt.getClaimAsString("email"));
        return ResponseEntity.ok(ApiResponse.ok(null, "Document archived"));
    }

    // ── Restore ──────────────────────────────────────────────────────────────

    @PostMapping("/{id}/restore")
    @PreAuthorize("hasPermission(null, 'archive:manage')")
    public ResponseEntity<ApiResponse<Void>> restore(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt
    ) {
        documentService.restore(id, jwt.getClaimAsString("email"));
        return ResponseEntity.ok(ApiResponse.ok(null, "Document restored"));
    }

    // ── Replace Document Content ──────────────────────────────────────────

    /**
     * Replace the binary content of an existing document.
     * Used by ecm-eforms to swap unsigned PDF with DocuSign-signed version.
     * Keeps the same document ID, metadata, and MinIO path — only the bytes change.
     */
    @PostMapping(value = "/{id}/replace", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<Void>> replaceContent(
            @PathVariable UUID id,
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
            @RequestHeader(value = INTERNAL_HEADER, required = false) String internalService) {

        if (internalService == null || internalService.isBlank()) {
            return ResponseEntity.status(403).body(
                    ApiResponse.error("Replace is only available for internal services", "FORBIDDEN"));
        }

        try {
            documentService.replaceContent(id, file);
            return ResponseEntity.ok(ApiResponse.ok(null, "Document content replaced"));
        } catch (Exception e) {
            log.error("Replace content failed for {}: {}", id, e.getMessage());
            return ResponseEntity.badRequest().body(
                    ApiResponse.error("Replace failed: " + e.getMessage(), "REPLACE_FAILED"));
        }
    }

    // ── Update Metadata (internal — batch classification, review) ────────

    /**
     * PATCH document metadata fields. Used by ecm-batch after auto-classification
     * or review approval to update category, customer, and classification source.
     * Internal-service only (X-Internal-Service header required).
     */
    @PutMapping("/{id}/metadata")
    public ResponseEntity<ApiResponse<Void>> updateMetadata(
            @PathVariable UUID id,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = INTERNAL_HEADER, required = false) String internalService) {

        if (internalService == null || internalService.isBlank()) {
            return ResponseEntity.status(403).body(
                    ApiResponse.error("Metadata update is only available for internal services", "FORBIDDEN"));
        }

        try {
            Document doc = documentRepository.findById(id).orElse(null);
            if (doc == null) {
                return ResponseEntity.notFound().build();
            }

            if (body.containsKey("categoryId") && body.get("categoryId") != null)
                doc.setCategoryId(((Number) body.get("categoryId")).intValue());
            if (body.containsKey("partyExternalId") && body.get("partyExternalId") != null)
                doc.setPartyExternalId(body.get("partyExternalId").toString());
            if (body.containsKey("classificationSource") && body.get("classificationSource") != null)
                doc.setClassificationSource(body.get("classificationSource").toString());
            if (body.containsKey("classificationConfidence") && body.get("classificationConfidence") != null)
                doc.setClassificationConfidence(new java.math.BigDecimal(body.get("classificationConfidence").toString()));
            if (body.containsKey("segmentId") && body.get("segmentId") != null)
                doc.setSegmentId(((Number) body.get("segmentId")).intValue());
            if (body.containsKey("productLineId") && body.get("productLineId") != null)
                doc.setProductLineId(((Number) body.get("productLineId")).intValue());

            documentRepository.save(doc);
            log.info("Document metadata updated by {}: id={}, fields={}", internalService, id, body.keySet());

            // Optional case-checklist auto-link (QR fast-path with a case context —
            // e.g. a branch-printed form for an existing case checklist item).
            // Never auto-creates a case or checklist row — only updates a row that
            // already exists, same UPDATE-only semantics as FormDocumentCreationService's
            // eforms-side linking. Anything without a matching row is left for manual
            // assignment, by design.
            if (body.containsKey("caseId") && body.containsKey("checklistItemId")
                    && body.get("caseId") != null && body.get("checklistItemId") != null) {
                try {
                    String caseId = body.get("caseId").toString();
                    int itemId = ((Number) body.get("checklistItemId")).intValue();
                    int rows = jdbc.update("""
                        UPDATE ecm_core.case_documents
                        SET document_id = ?, status = 'UPLOADED', uploaded_at = NOW(), updated_at = NOW()
                        WHERE id = ? AND case_id = ?::uuid
                        """, id, itemId, caseId);
                    if (rows > 0) {
                        log.info("Auto-linked document {} to case {} checklist item {}", id, caseId, itemId);
                    } else {
                        log.warn("No matching case_documents row for document {}, case {}, item {} — " +
                                "leaving for manual assignment", id, caseId, itemId);
                    }
                } catch (Exception e) {
                    log.warn("Failed to auto-link document {} to case checklist: {}", id, e.getMessage());
                }
            }

            // Publish document.classified event only when document is ACTIVE
            // (gated by confidence: ACTIVE = high confidence or manual)
            if (doc.getStatus() == DocumentStatus.ACTIVE
                    && body.containsKey("categoryId") && body.get("categoryId") != null) {
                try {
                    Map<String, Object> classifiedEvent = new java.util.HashMap<>();
                    classifiedEvent.put("documentId", doc.getId().toString());
                    classifiedEvent.put("categoryId", doc.getCategoryId());
                    classifiedEvent.put("classificationSource",
                            doc.getClassificationSource() != null ? doc.getClassificationSource() : "BATCH");
                    classifiedEvent.put("partyExternalId",
                            doc.getPartyExternalId() != null ? doc.getPartyExternalId() : "");
                    classifiedEvent.put("documentName", doc.getName());
                    classifiedEvent.put("uploadedBy",
                            doc.getUploadedByEmail() != null ? doc.getUploadedByEmail() : "");
                    rabbitTemplate.convertAndSend(
                            RabbitMqConfig.EXCHANGE, "document.classified", classifiedEvent);
                    log.info("Published document.classified event for documentId={}", id);

                    // Also trigger workflow now that category is known (deferred from upload)
                    Map<String, Object> workflowEvent = new java.util.HashMap<>();
                    workflowEvent.put("documentId", doc.getId().toString());
                    workflowEvent.put("documentName", doc.getName());
                    workflowEvent.put("categoryId", doc.getCategoryId());
                    workflowEvent.put("uploadedBy",
                            doc.getUploadedByEmail() != null ? doc.getUploadedByEmail() : "system");
                    workflowEvent.put("partyExternalId",
                            doc.getPartyExternalId() != null ? doc.getPartyExternalId() : "");
                    workflowEvent.put("correlationId", UUID.randomUUID().toString());
                    rabbitTemplate.convertAndSend(
                            RabbitMqConfig.EXCHANGE,
                            RabbitMqConfig.WORKFLOW_TRIGGER_ROUTING_KEY,
                            workflowEvent);
                    log.info("Workflow trigger published post-classification for documentId={}", id);
                } catch (Exception pubEx) {
                    log.warn("Failed to publish document.classified / workflow event for documentId={}: {}",
                            id, pubEx.getMessage());
                }
            }

            return ResponseEntity.ok(ApiResponse.ok(null, "Metadata updated"));
        } catch (Exception e) {
            log.error("Metadata update failed for {}: {}", id, e.getMessage());
            return ResponseEntity.badRequest().body(
                    ApiResponse.error("Metadata update failed: " + e.getMessage(), "UPDATE_FAILED"));
        }
    }

    // ── User-facing classify (assign category + customer) ────────────────

    /**
     * Classify a document — assign category and/or customer from the UI.
     * Triggers document.classified + workflow events when category is set.
     * Requires documents:write permission.
     */
    @PutMapping("/{id}/classify")
    @PreAuthorize("hasPermission(null, 'documents:write')")
    public ResponseEntity<ApiResponse<Void>> classifyDocument(
            @PathVariable UUID id,
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal Jwt jwt) {

        String classifiedBy = jwt.getClaimAsString("email");
        if (classifiedBy == null) classifiedBy = jwt.getSubject();

        try {
            Document doc = documentRepository.findById(id).orElse(null);
            if (doc == null) {
                return ResponseEntity.notFound().build();
            }

            boolean categoryChanged = false;
            if (body.containsKey("categoryId") && body.get("categoryId") != null) {
                doc.setCategoryId(((Number) body.get("categoryId")).intValue());
                categoryChanged = true;
            }
            if (body.containsKey("segmentId") && body.get("segmentId") != null) {
                doc.setSegmentId(((Number) body.get("segmentId")).intValue());
            }
            if (body.containsKey("productLineId") && body.get("productLineId") != null) {
                doc.setProductLineId(((Number) body.get("productLineId")).intValue());
            }
            if (body.containsKey("partyExternalId") && body.get("partyExternalId") != null) {
                doc.setPartyExternalId(body.get("partyExternalId").toString());
            }
            doc.setClassificationSource("MANUAL");
            // Manual classification completes the document — transition to ACTIVE
            if (doc.getStatus() == DocumentStatus.PENDING_CLASSIFICATION
                    || doc.getStatus() == DocumentStatus.NEEDS_ASSIGNMENT) {
                doc.setStatus(DocumentStatus.ACTIVE);
            }
            documentRepository.save(doc);
            log.info("Document {} classified by user {}: category={}, party={}, status={}",
                    id, classifiedBy, doc.getCategoryId(), doc.getPartyExternalId(), doc.getStatus());

            // Trigger downstream events (case auto-attach + workflow)
            if (categoryChanged) {
                try {
                    Map<String, Object> classifiedEvent = new java.util.HashMap<>();
                    classifiedEvent.put("documentId", doc.getId().toString());
                    classifiedEvent.put("categoryId", doc.getCategoryId());
                    classifiedEvent.put("classificationSource", "MANUAL");
                    classifiedEvent.put("partyExternalId",
                            doc.getPartyExternalId() != null ? doc.getPartyExternalId() : "");
                    classifiedEvent.put("documentName", doc.getName());
                    classifiedEvent.put("uploadedBy",
                            doc.getUploadedByEmail() != null ? doc.getUploadedByEmail() : "");
                    rabbitTemplate.convertAndSend(
                            RabbitMqConfig.EXCHANGE, "document.classified", classifiedEvent);

                    Map<String, Object> workflowEvent = new java.util.HashMap<>();
                    workflowEvent.put("documentId", doc.getId().toString());
                    workflowEvent.put("documentName", doc.getName());
                    workflowEvent.put("categoryId", doc.getCategoryId());
                    workflowEvent.put("uploadedBy", classifiedBy);
                    workflowEvent.put("partyExternalId",
                            doc.getPartyExternalId() != null ? doc.getPartyExternalId() : "");
                    workflowEvent.put("correlationId", UUID.randomUUID().toString());
                    rabbitTemplate.convertAndSend(
                            RabbitMqConfig.EXCHANGE,
                            RabbitMqConfig.WORKFLOW_TRIGGER_ROUTING_KEY,
                            workflowEvent);
                } catch (Exception pubEx) {
                    log.warn("Failed to publish events after classify for documentId={}: {}",
                            id, pubEx.getMessage());
                }
            }

            return ResponseEntity.ok(ApiResponse.ok(null, "Document classified"));
        } catch (Exception e) {
            log.error("Classify failed for {}: {}", id, e.getMessage());
            return ResponseEntity.badRequest().body(
                    ApiResponse.error("Classify failed: " + e.getMessage(), "CLASSIFY_FAILED"));
        }
    }

    // ── Spot Check: Approve / Flag ─────────────────────────────────────

    /**
     * Approve auto-classification — spot check confirms it's correct.
     * Marks the document as reviewed. No status change (already ACTIVE).
     */
    @PostMapping("/{id}/approve-classification")
    @PreAuthorize("hasPermission(null, 'batch:spot_check')")
    public ResponseEntity<ApiResponse<Void>> approveClassification(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt) {

        String reviewedBy = jwt.getClaimAsString("email");
        Document doc = documentRepository.findById(id).orElse(null);
        if (doc == null) return ResponseEntity.notFound().build();

        doc.setClassificationSource(
                doc.getClassificationSource() != null
                        ? doc.getClassificationSource() + "_VERIFIED"
                        : "VERIFIED");
        documentRepository.save(doc);

        log.info("Classification approved (spot check) for documentId={} by {}", id, reviewedBy);
        return ResponseEntity.ok(ApiResponse.ok(null, "Classification verified"));
    }

    /**
     * Flag auto-classification as incorrect — sends document back to NEEDS_ASSIGNMENT
     * so it appears in the Classification Queue for manual correction.
     */
    @PostMapping("/{id}/flag-classification")
    @PreAuthorize("hasPermission(null, 'batch:spot_check')")
    public ResponseEntity<ApiResponse<Void>> flagClassification(
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, Object> body,
            @AuthenticationPrincipal Jwt jwt) {

        String flaggedBy = jwt.getClaimAsString("email");
        Document doc = documentRepository.findById(id).orElse(null);
        if (doc == null) return ResponseEntity.notFound().build();

        doc.setStatus(DocumentStatus.NEEDS_ASSIGNMENT);
        doc.setClassificationSource("FLAGGED");
        documentRepository.save(doc);

        String reason = body != null && body.get("reason") != null ? body.get("reason").toString() : null;
        log.info("Classification flagged for documentId={} by {} reason={}", id, flaggedBy, reason);
        return ResponseEntity.ok(ApiResponse.ok(null, "Document sent to classification queue"));
    }

    // ── Checkout / Lock ──────────────────────────────────────────────────

    @PostMapping("/{id}/checkout")
    @PreAuthorize("hasPermission(null, 'documents:read')")
    public ResponseEntity<ApiResponse<Void>> checkout(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt) {
        documentService.checkout(id, jwt.getClaimAsString("email"));
        return ResponseEntity.ok(ApiResponse.ok(null, "Document locked"));
    }

    @PostMapping("/{id}/release")
    @PreAuthorize("hasPermission(null, 'documents:read')")
    public ResponseEntity<ApiResponse<Void>> release(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt) {
        documentService.release(id, jwt.getClaimAsString("email"));
        return ResponseEntity.ok(ApiResponse.ok(null, "Document unlocked"));
    }

    // ── Case Linkage Enrichment ─────────────────────────────────────────────

    /**
     * Batch-enriches a page of documents with case linkage info.
     * Single SQL query for all doc IDs — no N+1 problem.
     */
    private PagedResponse<DocumentResponse> enrichWithCaseInfo(PagedResponse<DocumentResponse> result) {
        try {
            if (result.content() == null || result.content().isEmpty()) return result;

            // Batch query: all active case links for documents in this page
            var docIds = result.content().stream().map(d -> d.id().toString()).toList();
            String placeholders = String.join(",", docIds.stream().map(id -> "'" + id + "'").toList());

            var caseLinks = jdbc.queryForList(
                "SELECT cd.document_id, c.id as case_id, " +
                "COALESCE(c.claimed_by_name, c.claimed_by, c.assigned_to_name, c.assigned_to, " +
                "c.assigned_to_group, 'Unassigned') as assignee " +
                "FROM ecm_core.case_documents cd " +
                "JOIN ecm_core.cases c ON c.id = cd.case_id " +
                "WHERE cd.document_id IN (" + placeholders + ") " +
                "AND c.status NOT IN ('COMPLETED', 'APPROVED', 'REJECTED', 'CANCELLED')");

            // Build lookup map: documentId → {caseId, assignee}
            var lookup = new java.util.HashMap<String, java.util.Map<String, String>>();
            for (var row : caseLinks) {
                String docId = row.get("document_id").toString();
                lookup.put(docId, java.util.Map.of(
                        "caseId", row.get("case_id").toString(),
                        "assignee", row.get("assignee") != null ? row.get("assignee").toString() : ""
                ));
            }

            // Create enriched copies (records are immutable, so reconstruct)
            var enriched = result.content().stream().map(doc -> {
                var link = lookup.get(doc.id().toString());
                if (link == null) return doc;
                return new DocumentResponse(
                        doc.id(), doc.name(), doc.originalFilename(), doc.mimeType(),
                        doc.fileSizeBytes(), doc.categoryId(), doc.categoryName(),
                        doc.departmentId(), doc.uploadedByEmail(), doc.status(),
                        doc.version(), doc.parentDocId(), doc.isLatestVersion(),
                        doc.ocrCompleted(), doc.extractedText(), doc.extractedFields(),
                        doc.tags(),
                        doc.classificationSource(), doc.classificationConfidence(), doc.lockType(),
                        doc.createdAt(), doc.updatedAt(),
                        doc.segmentId(), doc.segmentName(), doc.productLineId(), doc.productLineName(),
                        doc.downloadUrl(), doc.partyExternalId(),
                        doc.lockedBy(), doc.lockedAt(), doc.lockExpiresAt(),
                        doc.pipelineState(),
                        link.get("caseId"), link.get("assignee")
                );
            }).toList();

            return new PagedResponse<>(enriched, result.page(), result.size(),
                    result.totalElements(), result.totalPages(), result.last());
        } catch (Exception e) {
            log.debug("Case enrichment skipped: {}", e.getMessage());
            return result;
        }
    }

    // ── Case Linkage Info ─────────────────────────────────────────────────

    @GetMapping("/{id}/case-info")
    @PreAuthorize("hasPermission(null, 'documents:read')")
    public ResponseEntity<ApiResponse<java.util.Map<String, Object>>> getCaseInfo(
            @PathVariable UUID id) {
        var ownership = stateGuard.findCaseOwnership(id);
        if (ownership == null) {
            return ResponseEntity.ok(ApiResponse.ok(java.util.Map.of("linked", false)));
        }
        return ResponseEntity.ok(ApiResponse.ok(java.util.Map.of(
                "linked", true,
                "caseId", ownership.caseId().toString(),
                "caseStatus", ownership.caseStatus() != null ? ownership.caseStatus() : "",
                "assignedTo", ownership.assignedToName() != null ? ownership.assignedToName() :
                              ownership.assignedTo() != null ? ownership.assignedTo() : "",
                "claimedBy", ownership.claimedByName() != null ? ownership.claimedByName() :
                             ownership.claimedBy() != null ? ownership.claimedBy() : "",
                "assignedToGroup", ownership.assignedToGroup() != null ? ownership.assignedToGroup() : ""
        )));
    }

    // ── Document Versioning ─────────────────────────────────────────────────

    /**
     * Upload a new version of an existing document.
     * Creates a new document record linked to the parent via parent_doc_id.
     * The parent's is_latest_version is set to false.
     */
    @PostMapping(value = "/{id}/versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasPermission(null, 'DOCUMENT:UPLOAD')")
    public ResponseEntity<ApiResponse<DocumentResponse>> uploadNewVersion(
            @PathVariable UUID id,
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal org.springframework.security.oauth2.jwt.Jwt jwt) {
        String email = jwt != null ? jwt.getClaimAsString("email") : "system";
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(documentService.uploadNewVersion(id, file, email), "New version uploaded"));
    }

    /**
     * Get the version history for a document.
     * Returns all versions in the chain, ordered by version number ascending.
     */
    @GetMapping("/{id}/versions")
    @PreAuthorize("hasPermission(null, 'DOCUMENT:VIEW')")
    public ResponseEntity<ApiResponse<java.util.List<DocumentResponse>>> getVersionHistory(
            @PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(documentService.getVersionHistory(id)));
    }
}