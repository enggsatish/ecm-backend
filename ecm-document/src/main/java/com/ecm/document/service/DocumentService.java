package com.ecm.document.service;

import com.ecm.document.dto.DocumentResponse;
import com.ecm.document.dto.DocumentUploadRequest;
import com.ecm.document.dto.PagedResponse;
import com.ecm.document.entity.Document;
import com.ecm.document.entity.DocumentStatus;
import com.ecm.document.storage.StorageObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface DocumentService {

    /**
     * @param uploadedByUserId  integer PK from ecm_core.users — resolved by the controller
     *                          from the JWT subject via the user lookup service
     */
    DocumentResponse upload(MultipartFile file,
                            DocumentUploadRequest metadata,
                            Integer uploadedByUserId,
                            String  uploadedByEmail);
    PagedResponse<DocumentResponse> search(String query, Pageable pageable);

    PagedResponse<DocumentResponse> listAll(Pageable pageable);

    PagedResponse<DocumentResponse> listByParty(String partyExternalId, Pageable pageable);

    /** List documents in PENDING_CLASSIFICATION or NEEDS_ASSIGNMENT status. */
    PagedResponse<DocumentResponse> listNeedsClassification(Pageable pageable);

    /** List auto-classified documents (ACTIVE + classificationSource=AUTO_CLASSIFIED) for spot check. */
    PagedResponse<DocumentResponse> listAutoClassified(Pageable pageable);

    DocumentResponse getById(UUID id);

    StorageObject download(UUID id);

    void delete(UUID id, Integer deletedByUserId);

    /** Soft-delete with reason (admin only). Does NOT remove binary. */
    void softDelete(UUID id, String reason, String deletedByEmail);

    /** Move document binary to archive bucket. Status → ARCHIVED. */
    void archive(UUID id, String archivedByEmail);

    /** Restore archived document back to active bucket. Status → ACTIVE. */
    void restore(UUID id, String restoredByEmail);

    /** Check out (lock) document for exclusive review. Auto-expires in 1 hour. */
    void checkout(UUID id, String lockedByEmail);

    /** Release document lock. Owner or admin can release. */
    void release(UUID id, String releasedByEmail);

    /** Replace the binary content of an existing document (same ID, same path). */
    void replaceContent(UUID id, org.springframework.web.multipart.MultipartFile file);

    /** Upload a new version of an existing document. Creates new record, links parent, marks old as not-latest. */
    DocumentResponse uploadNewVersion(UUID parentId, MultipartFile file, String uploadedByEmail);

    /** Get version history for a document (all versions in the chain). */
    java.util.List<DocumentResponse> getVersionHistory(UUID documentId);
}