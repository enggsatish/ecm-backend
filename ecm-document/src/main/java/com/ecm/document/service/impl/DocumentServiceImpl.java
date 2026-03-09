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
    private static final int MAX_PAGE   = 500;

    @Value("${ecm.storage.bucket:ecm-documents}")
    private String storageBucket;

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

        // 1. Store in MinIO first — generates blobPath with bucket prefix
        String blobPath = storageService.store(storageBucket, documentId, file, metadata);

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
                .blobStoragePath(blobPath)
                .categoryId(metadata != null ? metadata.categoryId() : null)
                .departmentId(metadata != null ? metadata.departmentId() : null)
                .uploadedBy(uploadedByUserId)
                .uploadedByEmail(uploadedByEmail)
                .status(DocumentStatus.PENDING_OCR)
                .metadata(metadata != null ? metadata.metadata() : null)
                .tags(metadata != null ? metadata.tags() : null)
                .segmentId(metadata != null ? metadata.segmentId() : null)         // NEW
                .productLineId(metadata != null ? metadata.productLineId() : null) // NEW
                .partyExternalId(metadata != null ? metadata.partyExternalId() : null)
                .build();

        try {
            document = documentRepository.save(document);
            indexSync.updateStatus(document.getId(), document.getStatus().name());
        } catch (Exception dbEx) {
            // DB failed after MinIO succeeded — clean up orphaned object
            log.error("DB save failed after MinIO upload — attempting MinIO cleanup for key={}", blobPath);
            try {
                String[] parts = splitBlobPath(blobPath);
                storageService.delete(parts[0], parts[1]);
            } catch (Exception cleanupEx) {
                log.error("MinIO cleanup also failed — orphaned object at path={}", blobPath);
            }
            throw dbEx; // rethrow so transaction rolls back and audit records FAILURE
        }

        log.info("Document saved: id={}, uploadedBy={}", documentId, uploadedByUserId);

        // 4. Publish OCR event — best-effort, never rolls back upload
        publishOcrEvent(document);
        // Publish workflow trigger — always best-effort, never blocks upload
        publishWorkflowTriggerEvent(document);

        return documentMapper.toResponse(document);
    }
// Search document
    @Override
    @Transactional(readOnly = true)
    public PagedResponse<DocumentResponse> search(String query, Pageable pageable) {
        if (query == null || query.isBlank()) {
            return listAll(pageable);
        }
        return PagedResponse.of(
                documentRepository
                        .searchByName(query.trim(), DocumentStatus.DELETED, pageable)
                        .map(documentMapper::toResponse)
        );
    }

    // ── List ──────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public PagedResponse<DocumentResponse> listAll(Pageable pageable) {
        // Cap page number: prevent database from scanning millions of rows
        int safePage = Math.min(pageable.getPageNumber(), MAX_PAGE);
        Pageable safePageable = PageRequest.of(
                safePage, pageable.getPageSize(), pageable.getSort());
        return PagedResponse.of(
                documentRepository
                        .findByStatusNotOrderByCreatedAtDesc(DocumentStatus.DELETED, safePageable)
                        .map(documentMapper::toResponse)
        );
    }

    // ── Get by ID ─────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public DocumentResponse getById(UUID id) {
        return documentRepository
                .findByIdAndStatusNot(id, DocumentStatus.DELETED)
                .map(documentMapper::toResponse)
                .orElseThrow(() -> new DocumentNotFoundException(id));
    }

    // ── Download ──────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public StorageObject download(UUID id) {
        Document doc = documentRepository
                .findByIdAndStatusNot(id, DocumentStatus.DELETED)
                .orElseThrow(() -> new DocumentNotFoundException(id));

        String[] parts = splitBlobPath(doc.getBlobStoragePath());
        return storageService.retrieve(parts[0], parts[1]);
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
            String[] parts = splitBlobPath(doc.getBlobStoragePath());
            storageService.delete(parts[0], parts[1]);
        } catch (Exception ex) {
            log.warn("MinIO delete failed for id={} — manual cleanup may be needed", id, ex);
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private String resolveContentType(MultipartFile file) {
        String ct = file.getContentType();
        return (ct != null && !ct.isBlank()) ? ct : "application/octet-stream";
    }

    /**
     * blobStoragePath = "{bucket}/{year}/{month}/{uuid}/{filename}"
     * Returns [ bucket, key ]
     */
    private String[] splitBlobPath(String blobPath) {
        int slash = blobPath.indexOf('/');
        if (slash > 0) {
            return new String[]{ blobPath.substring(0, slash), blobPath.substring(slash + 1) };
        }
        return new String[]{ storageBucket, blobPath };
    }

    private void publishOcrEvent(Document doc) {
        try {
            String[] parts = splitBlobPath(doc.getBlobStoragePath());
            OcrRequestEvent event = new OcrRequestEvent(
                    doc.getId(), parts[0], parts[1],
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