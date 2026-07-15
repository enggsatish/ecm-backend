package com.ecm.document.service.impl;

import com.ecm.common.audit.AuditLog;
import com.ecm.document.config.RabbitMqConfig;
import com.ecm.document.dto.DocumentResponse;
import com.ecm.document.dto.DocumentUploadRequest;
import com.ecm.document.dto.PagedResponse;
import com.ecm.document.entity.Document;
import com.ecm.document.entity.DocumentStatus;
import com.ecm.document.event.OcrRequestEvent;
import com.ecm.document.exception.DocumentNotFoundException;
import com.ecm.document.mapper.DocumentMapper;
import com.ecm.document.repository.DocumentRepository;
import com.ecm.document.service.DocumentIndexSyncService;
import com.ecm.document.service.DocumentService;
import com.ecm.document.service.DocumentStateGuard;
import com.ecm.document.service.HierarchyEnricher;
import com.ecm.document.storage.DocumentStorageService;
import com.ecm.document.storage.StorageObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentServiceImpl implements DocumentService {

    private final DocumentRepository     documentRepository;
    private final DocumentStorageService storageService;
    private final DocumentMapper         documentMapper;
    private final RabbitTemplate         rabbitTemplate;
    private final DocumentIndexSyncService indexSync;
    private final DocumentStateGuard stateGuard;
    private final HierarchyEnricher hierarchyEnricher;
    private static final int MAX_PAGE   = 500;

    @Value("${ecm.storage.bucket:ecm-documents}")
    private String storageBucket;

    @Value("${ecm.storage.archive-bucket:ecm-archive}")
    private String archiveBucket;

    // ── Upload ────────────────────────────────────────────────────────────────
    //
    // ANNOTATION ORDER — @AuditLog MUST be outer, @Transactional MUST be inner.
    //
    // Spring AOP processes annotations from outermost to innermost at invocation time.
    // With @AuditLog outer: audit intercepts the FULL operation including the
    // committed DB state. If the transaction rolls back, audit records FAILURE.
    //
    // Previous order (@Transactional outer, @AuditLog inner) meant audit ran
    // INSIDE the transaction — a rollback after audit wrote "SUCCESS" would leave
    // a lying audit record. Especially dangerous for MinIO-succeeds/DB-fails cases.

    @Override
    @AuditLog(event = "DOCUMENT_UPLOAD", resourceType = "DOCUMENT", severity = "INFO")
    @Transactional
    public DocumentResponse upload(MultipartFile file,
                                   DocumentUploadRequest metadata,
                                   Integer uploadedByUserId, String uploadedByEmail) {

        UUID documentId = UUID.randomUUID();

        // 1. Store in MinIO first — returns the object key (no bucket prefix)
        log.info("document id : {} metadata : {} ", documentId, metadata);

        String storageKey = storageService.store(storageBucket, documentId, file, metadata);

        // 2. Resolve display name
        String displayName = (metadata != null
                && metadata.name() != null
                && !metadata.name().isBlank())
                ? metadata.name()
                : file.getOriginalFilename();

        // 3. Persist metadata — if this fails, MinIO file is orphaned (best-effort cleanup below)
        Document document = Document.builder()
                .id(documentId)
                .name(displayName)
                .originalFilename(file.getOriginalFilename())
                .mimeType(resolveContentType(file))
                .fileSizeBytes(file.getSize())
                .storageKey(storageKey)
                .storageBucket(storageBucket)
                .categoryId(metadata != null ? metadata.categoryId() : null)
                .departmentId(metadata != null ? metadata.departmentId() : null)
                .uploadedBy(uploadedByUserId)
                .uploadedByEmail(uploadedByEmail)
                .status(DocumentStatus.PENDING_OCR)
                .classificationSource(metadata != null && metadata.categoryId() != null ? "MANUAL" : null)
                .metadata(metadata != null ? metadata.metadata() : null)
                .tags(metadata != null ? metadata.tags() : null)
                .segmentId(metadata != null ? metadata.segmentId() : null)
                .productLineId(metadata != null ? metadata.productLineId() : null)
                .partyExternalId(metadata != null ? metadata.partyExternalId() : null)
                .pipelineState("[{\"step\":\"UPLOAD\",\"status\":\"DONE\",\"group\":\"ingest\","
                        + "\"label\":\"Uploaded\",\"detail\":\""
                        + file.getSize() / 1024 + "KB " + resolveContentType(file) + "\","
                        + "\"ts\":\"" + java.time.Instant.now() + "\"}]")
                .build();

        try {
            document = documentRepository.save(document);
            indexSync.updateStatus(document.getId(), document.getStatus().name());
        } catch (Exception dbEx) {
            // DB failed after MinIO succeeded — clean up orphaned object
            log.error("DB save failed after MinIO upload — attempting MinIO cleanup for key={}", storageKey);
            try {
                storageService.delete(storageBucket, storageKey);
            } catch (Exception cleanupEx) {
                log.error("MinIO cleanup also failed — orphaned object at bucket={}, key={}", storageBucket, storageKey);
            }
            throw dbEx; // rethrow so transaction rolls back and audit records FAILURE
        }

        log.info("Document saved: id={}, uploadedBy={}", documentId, uploadedByUserId);

        // 4. Publish OCR event — best-effort, never rolls back upload
        publishOcrEvent(document);

        // 5. Publish workflow trigger — skip for case checklist uploads (handled by case flow)
        //    Also defer if category is null — workflow will trigger after OCR + classification
        boolean skip = metadata != null && Boolean.TRUE.equals(metadata.skipWorkflow());
        boolean hasCategoryToRoute = metadata != null && metadata.categoryId() != null;
        if (!skip && hasCategoryToRoute) {
            publishWorkflowTriggerEvent(document);
        } else if (!hasCategoryToRoute) {
            log.debug("Workflow trigger deferred for documentId={} — category not yet assigned, will trigger after classification", documentId);
        } else {
            log.debug("Workflow trigger skipped for documentId={} (case checklist upload)", documentId);
        }

        return documentMapper.toResponse(document);
    }
// Search document
    @Override
    @Transactional(readOnly = true)
    public PagedResponse<DocumentResponse> search(String query, Pageable pageable) {
        if (query == null || query.isBlank()) {
            return listAll(pageable);
        }
        return enrichPage(PagedResponse.of(
                documentRepository
                        .searchByName(query.trim(), DocumentStatus.DELETED, pageable)
                        .map(documentMapper::toResponse)
        ));
    }

    // ── List ──────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public PagedResponse<DocumentResponse> listAll(Pageable pageable) {
        // Cap page number: prevent database from scanning millions of rows
        int safePage = Math.min(pageable.getPageNumber(), MAX_PAGE);
        Pageable safePageable = PageRequest.of(
                safePage, pageable.getPageSize(), pageable.getSort());
        return enrichPage(PagedResponse.of(
                documentRepository
                        .findByStatusNotOrderByCreatedAtDesc(DocumentStatus.DELETED, safePageable)
                        .map(documentMapper::toResponse)
        ));
    }

    // ── List by Party ─────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public PagedResponse<DocumentResponse> listByParty(String partyExternalId, Pageable pageable) {
        return enrichPage(PagedResponse.of(
                documentRepository
                        .findByPartyExternalIdAndStatusNotOrderByCreatedAtDesc(
                                partyExternalId, DocumentStatus.DELETED, pageable)
                        .map(documentMapper::toResponse)
        ));
    }

    // ── List Needs Classification ───────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public PagedResponse<DocumentResponse> listNeedsClassification(Pageable pageable) {
        return enrichPage(PagedResponse.of(
                documentRepository
                        .findByStatusInOrderByCreatedAtDesc(
                                java.util.List.of(DocumentStatus.PENDING_CLASSIFICATION,
                                        DocumentStatus.NEEDS_ASSIGNMENT),
                                pageable)
                        .map(documentMapper::toResponse)
        ));
    }

    @Override
    @Transactional(readOnly = true)
    public PagedResponse<DocumentResponse> listAutoClassified(Pageable pageable) {
        return enrichPage(PagedResponse.of(
                documentRepository
                        .findByStatusAndClassificationSourceOrderByCreatedAtDesc(
                                DocumentStatus.ACTIVE, "AUTO_CLASSIFIED", pageable)
                        .map(documentMapper::toResponse)
        ));
    }

    // ── Get by ID ─────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public DocumentResponse getById(UUID id) {
        DocumentResponse doc = documentRepository
                .findByIdAndStatusNot(id, DocumentStatus.DELETED)
                .map(documentMapper::toResponse)
                .orElseThrow(() -> new DocumentNotFoundException(id));
        return hierarchyEnricher.enrich(doc);
    }

    /** Batch-enrich a paged result with hierarchy names. */
    private PagedResponse<DocumentResponse> enrichPage(PagedResponse<DocumentResponse> page) {
        if (page.content() == null || page.content().isEmpty()) return page;
        var enriched = hierarchyEnricher.enrich(page.content());
        return new PagedResponse<>(enriched, page.page(), page.size(),
                page.totalElements(), page.totalPages(), page.last());
    }

    // ── Download ──────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public StorageObject download(UUID id) {
        Document doc = documentRepository
                .findByIdAndStatusNot(id, DocumentStatus.DELETED)
                .orElseThrow(() -> new DocumentNotFoundException(id));

        return storageService.retrieve(doc.getStorageBucket(), doc.getStorageKey());
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    @Override
    @AuditLog(event = "DOCUMENT_DELETE", resourceType = "DOCUMENT", severity = "WARN")
    @Transactional
    public void delete(UUID id, Integer deletedByUserId) {
        Document doc = documentRepository
                .findByIdAndStatusNot(id, DocumentStatus.DELETED)
                .orElseThrow(() -> new DocumentNotFoundException(id));

        doc.setStatus(DocumentStatus.DELETED);
        documentRepository.save(doc);
        // update opensearch state.
        indexSync.updateStatus(doc.getId(), doc.getStatus().name());
        log.info("Document soft-deleted: id={}, by={}", id, deletedByUserId);

        // Best-effort MinIO removal — failure does not roll back soft-delete
        try {
            storageService.delete(doc.getStorageBucket(), doc.getStorageKey());
        } catch (Exception ex) {
            log.warn("MinIO delete failed for id={} — manual cleanup may be needed", id, ex);
        }
    }

    // ── Soft Delete (admin only, with reason) ──────────────────────────────────

    @Override
    @AuditLog(event = "DOCUMENT_SOFT_DELETE", resourceType = "DOCUMENT", severity = "WARN")
    @Transactional
    public void softDelete(UUID id, String reason, String deletedByEmail) {
        stateGuard.assertCanDelete(id);

        Document doc = documentRepository.findById(id)
                .orElseThrow(() -> new DocumentNotFoundException(id));

        // Check for active case links (cross-schema via JdbcTemplate would be needed,
        // but since ecm-document doesn't have JdbcTemplate to ecm_core, we skip
        // the case check here — the frontend should enforce this)

        doc.setStatus(DocumentStatus.DELETED);
        doc.setDeletedAt(java.time.Instant.now());
        doc.setDeletedBy(deletedByEmail);
        doc.setDeleteReason(reason);
        documentRepository.save(doc);
        indexSync.updateStatus(doc.getId(), doc.getStatus().name());

        log.info("Document soft-deleted: id={}, reason='{}', by={}", id, reason, deletedByEmail);
        // Binary is NOT removed — kept for potential restore/audit
    }

    // ── Archive (move to archive bucket) ────────────────────────────────────

    @Override
    @AuditLog(event = "DOCUMENT_ARCHIVED", resourceType = "DOCUMENT")
    @Transactional
    public void archive(UUID id, String archivedByEmail) {
        stateGuard.assertCanArchive(id);

        Document doc = documentRepository.findById(id)
                .orElseThrow(() -> new DocumentNotFoundException(id));

        if (doc.getStatus() != DocumentStatus.ACTIVE && doc.getStatus() != DocumentStatus.PENDING_OCR
                && doc.getStatus() != DocumentStatus.OCR_FAILED) {
            throw new IllegalStateException("Only ACTIVE documents can be archived. Current status: " + doc.getStatus());
        }

        // Move binary: active bucket → archive bucket
        String sourceBucket = doc.getStorageBucket();
        String key = doc.getStorageKey();

        try {
            storageService.copy(sourceBucket, key, archiveBucket);
            storageService.delete(sourceBucket, key);

            // Update bucket to point to archive bucket (key stays the same)
            doc.setStorageBucket(archiveBucket);
            doc.setStatus(DocumentStatus.ARCHIVED);
            doc.setArchivedAt(java.time.Instant.now());
            doc.setArchivedBy(archivedByEmail);
            documentRepository.save(doc);
            indexSync.updateStatus(doc.getId(), doc.getStatus().name());

            log.info("Document archived: id={}, by={}, bucket={}, key={}", id, archivedByEmail, archiveBucket, key);
        } catch (Exception ex) {
            log.error("Archive failed for document id={}: {}", id, ex.getMessage());
            throw new IllegalStateException("Failed to archive document: " + ex.getMessage(), ex);
        }
    }

    // ── Restore (move back from archive bucket) ─────────────────────────────

    @Override
    @AuditLog(event = "DOCUMENT_RESTORED", resourceType = "DOCUMENT")
    @Transactional
    public void restore(UUID id, String restoredByEmail) {
        Document doc = documentRepository.findById(id)
                .orElseThrow(() -> new DocumentNotFoundException(id));

        if (doc.getStatus() != DocumentStatus.ARCHIVED) {
            throw new IllegalStateException("Only ARCHIVED documents can be restored. Current status: " + doc.getStatus());
        }

        String key = doc.getStorageKey(); // key is the same, just different bucket

        try {
            storageService.copy(archiveBucket, key, storageBucket);
            storageService.delete(archiveBucket, key);

            doc.setStorageBucket(storageBucket);
            doc.setStatus(DocumentStatus.ACTIVE);
            doc.setArchivedAt(null);
            doc.setArchivedBy(null);
            documentRepository.save(doc);
            indexSync.updateStatus(doc.getId(), doc.getStatus().name());

            log.info("Document restored: id={}, by={}", id, restoredByEmail);
        } catch (Exception ex) {
            log.error("Restore failed for document id={}: {}", id, ex.getMessage());
            throw new IllegalStateException("Failed to restore document: " + ex.getMessage(), ex);
        }
    }

    // ── Checkout / Lock ───────────────────────────────────────────────────────

    private static final long LOCK_DURATION_HOURS = 1;

    @Override
    @AuditLog(event = "DOCUMENT_CHECKOUT", resourceType = "DOCUMENT")
    @Transactional
    public void checkout(UUID id, String lockedByEmail) {
        Document doc = documentRepository.findById(id)
                .orElseThrow(() -> new DocumentNotFoundException(id));

        // If document is linked to an active case, only the case owner can lock it.
        // This prevents reviewers or other users from locking case-linked documents.
        stateGuard.assertCanModify(id, lockedByEmail, "lock");

        // Check if already locked by someone else (and lock hasn't expired)
        if (doc.getLockedBy() != null && doc.getLockExpiresAt() != null
                && doc.getLockExpiresAt().isAfter(java.time.Instant.now())
                && !doc.getLockedBy().equals(lockedByEmail)) {
            throw new IllegalStateException(
                    "Document is locked by " + doc.getLockedBy() +
                    " until " + doc.getLockExpiresAt() + ". Ask them to release it.");
        }

        // Already locked by same user — extend the lock
        if (doc.getLockedBy() != null && doc.getLockedBy().equals(lockedByEmail)
                && doc.getLockExpiresAt() != null && doc.getLockExpiresAt().isAfter(java.time.Instant.now())) {
            doc.setLockExpiresAt(java.time.Instant.now().plus(LOCK_DURATION_HOURS, java.time.temporal.ChronoUnit.HOURS));
            documentRepository.save(doc);
            log.info("Document lock extended: id={}, by={}, newExpiry={}", id, lockedByEmail, doc.getLockExpiresAt());
            return;
        }

        doc.setLockedBy(lockedByEmail);
        doc.setLockedAt(java.time.Instant.now());
        doc.setLockExpiresAt(java.time.Instant.now().plus(LOCK_DURATION_HOURS, java.time.temporal.ChronoUnit.HOURS));
        documentRepository.save(doc);

        log.info("Document locked: id={}, by={}, expires={}",
                id, lockedByEmail, doc.getLockExpiresAt());
    }

    @Override
    @AuditLog(event = "DOCUMENT_RELEASE", resourceType = "DOCUMENT")
    @Transactional
    public void release(UUID id, String releasedByEmail) {
        Document doc = documentRepository.findById(id)
                .orElseThrow(() -> new DocumentNotFoundException(id));

        if (doc.getLockedBy() == null) {
            log.debug("Document {} is not locked — nothing to release", id);
            return;
        }

        // Only the lock owner can release (admin override would bypass via direct DB update)
        if (!doc.getLockedBy().equals(releasedByEmail)) {
            throw new IllegalStateException(
                    "Cannot release — document is locked by " + doc.getLockedBy() +
                    ", not " + releasedByEmail);
        }

        doc.setLockedBy(null);
        doc.setLockedAt(null);
        doc.setLockExpiresAt(null);
        documentRepository.save(doc);

        log.info("Document released: id={}, by={}", id, releasedByEmail);
    }

    // ── Replace Content ────────────────────────────────────────────────────────

    @Override
    @Transactional
    public void replaceContent(UUID id, MultipartFile file) {
        Document doc = documentRepository.findById(id)
                .orElseThrow(() -> new DocumentNotFoundException(id));

        String bucket = doc.getStorageBucket();
        String key = doc.getStorageKey();
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("Document has no storage key — cannot replace");
        }

        try {
            // Overwrite the existing MinIO object using the storage service
            storageService.storeRaw(bucket, key, file.getInputStream(), file.getSize(),
                    resolveContentType(file));

            // Update file size
            doc.setFileSizeBytes(file.getSize());
            doc.setUpdatedAt(java.time.Instant.now());
            documentRepository.save(doc);

            log.info("Document content replaced: id={}, size={} bytes, bucket={}, key={}",
                    id, file.getSize(), bucket, key);
        } catch (Exception e) {
            throw new RuntimeException("Failed to replace document content: " + e.getMessage(), e);
        }
    }

    // ── Versioning ────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public DocumentResponse uploadNewVersion(UUID parentId, MultipartFile file, String uploadedByEmail) {
        Document parent = documentRepository.findById(parentId)
                .orElseThrow(() -> new DocumentNotFoundException(parentId));

        // Generate new document ID for the new version
        UUID newDocId = UUID.randomUUID();
        int newVersion = parent.getVersion() + 1;

        // Store in MinIO
        String storageKey = storageService.store(storageBucket, newDocId, file, null);

        // Create new version record — inherits metadata from parent
        Document newDoc = Document.builder()
                .id(newDocId)
                .name(parent.getName())
                .originalFilename(file.getOriginalFilename())
                .mimeType(resolveContentType(file))
                .fileSizeBytes(file.getSize())
                .storageKey(storageKey)
                .storageBucket(storageBucket)
                .categoryId(parent.getCategoryId())
                .departmentId(parent.getDepartmentId())
                .segmentId(parent.getSegmentId())
                .productLineId(parent.getProductLineId())
                .partyExternalId(parent.getPartyExternalId())
                .uploadedBy(parent.getUploadedBy())
                .uploadedByEmail(uploadedByEmail)
                .status(parent.getStatus())
                .version(newVersion)
                .parentDocId(parentId)
                .isLatestVersion(true)
                .ocrCompleted(false)
                .build();

        // Mark parent as not latest
        parent.setIsLatestVersion(false);
        documentRepository.save(parent);

        newDoc = documentRepository.save(newDoc);

        log.info("New version created: id={}, parentId={}, version={}, uploadedBy={}",
                newDocId, parentId, newVersion, uploadedByEmail);

        // Publish OCR event for the new version
        publishOcrEvent(newDoc);

        return documentMapper.toResponse(newDoc);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DocumentResponse> getVersionHistory(UUID documentId) {
        Document doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));

        // Walk up to find the root of the chain
        UUID rootId = documentId;
        UUID currentParent = doc.getParentDocId();
        int safety = 50; // prevent infinite loop
        while (currentParent != null && safety-- > 0) {
            Optional<Document> parentDoc = documentRepository.findById(currentParent);
            if (parentDoc.isPresent()) {
                rootId = currentParent;
                currentParent = parentDoc.get().getParentDocId();
            } else {
                break;
            }
        }

        // Collect all versions: root + all children in chain
        List<Document> versions = new java.util.ArrayList<>();
        Document root = documentRepository.findById(rootId).orElse(doc);
        versions.add(root);

        // Walk down from root
        collectChildren(root.getId(), versions, 50);

        // Sort by version ascending
        versions.sort(java.util.Comparator.comparingInt(d -> d.getVersion() != null ? d.getVersion() : 1));

        return versions.stream().map(documentMapper::toResponse).toList();
    }

    private void collectChildren(UUID parentId, List<Document> versions, int maxDepth) {
        if (maxDepth <= 0) return;
        List<Document> children = documentRepository.findByParentDocIdOrderByVersionAsc(parentId);
        for (Document child : children) {
            versions.add(child);
            collectChildren(child.getId(), versions, maxDepth - 1);
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private String resolveContentType(MultipartFile file) {
        String ct = file.getContentType();
        return (ct != null && !ct.isBlank()) ? ct : "application/octet-stream";
    }

    private void publishOcrEvent(Document doc) {
        try {
            OcrRequestEvent event = new OcrRequestEvent(
                    doc.getId(), doc.getStorageBucket(), doc.getStorageKey(),
                    doc.getMimeType(), String.valueOf(doc.getUploadedBy()),
                    doc.getCategoryId(), doc.getName()
            );
            rabbitTemplate.convertAndSend(
                    RabbitMqConfig.EXCHANGE, RabbitMqConfig.OCR_ROUTING_KEY, event);
            log.debug("OCR event published for documentId={}", doc.getId());
        } catch (Exception ex) {
            log.error("RabbitMQ unavailable for OCR event (documentId={}) — " +
                    "applying PoC synchronous fallback: PENDING_OCR → ACTIVE", doc.getId(), ex);
            // ── PoC Synchronous Fallback ──────────────────────────────────────────
            // When RabbitMQ is down or the virtual-host isn't ready, the async OCR
            // pipeline can't fire. For local development / PoC we immediately
            // transition the document to ACTIVE so it's visible in the document list.
            //
            // In production: remove this block and ensure RabbitMQ is always available
            // before the application starts (health check in docker-compose / k8s).
            try {
//                documentRepository.findById(doc.getId()).ifPresent(d -> {
//                    if (d.getStatus() == DocumentStatus.PENDING_OCR) {
//                        d.setStatus(DocumentStatus.ACTIVE);
//                        documentRepository.save(d);
//                        log.info("Synchronous OCR fallback: document {} → ACTIVE", d.getId());
//                    }
//                });
            } catch (Exception fallbackEx) {
                log.error("Synchronous OCR fallback also failed for documentId={}", doc.getId(), fallbackEx);
            }
        }
    }

    /**
     * Publishes a document.workflow.trigger event to the ecm.documents exchange.
     * This is separate from the OCR event — OCR is for text extraction;
     * this event drives Flowable workflow routing by category + partyExternalId.
     *
     * partyExternalId=null → DocumentUploadedListener routes to unlinked-document-triage.
     * partyExternalId=set  → normal category/product template resolution.
     *
     * Always best-effort — failure never rolls back the upload.
     */
    private void publishWorkflowTriggerEvent(Document doc) {
        try {
            Map<String, Object> event = new java.util.HashMap<>();
            event.put("documentId",      doc.getId().toString());
            event.put("documentName",    doc.getName());
            event.put("categoryId",      doc.getCategoryId());
            event.put("uploadedBy",      doc.getUploadedByEmail());
            event.put("partyExternalId", doc.getPartyExternalId());  // ← null triggers triage
            event.put("correlationId",   UUID.randomUUID().toString());

            rabbitTemplate.convertAndSend(
                    RabbitMqConfig.EXCHANGE,
                    RabbitMqConfig.WORKFLOW_TRIGGER_ROUTING_KEY,
                    event);
            log.debug("Workflow trigger published for documentId={}, party={}",
                    doc.getId(), doc.getPartyExternalId());
        } catch (Exception ex) {
            log.warn("Workflow trigger publish failed for documentId={} — upload succeeded, workflow may not start: {}",
                    doc.getId(), ex.getMessage());
        }
    }
}