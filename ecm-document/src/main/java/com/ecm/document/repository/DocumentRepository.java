package com.ecm.document.repository;

import com.ecm.document.entity.Document;
import com.ecm.document.entity.DocumentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    /** All non-deleted documents, newest first. */
    Page<Document> findByStatusNotOrderByCreatedAtDesc(DocumentStatus status, Pageable pageable);

    /** Single non-deleted document by ID. */
    Optional<Document> findByIdAndStatusNot(UUID id, DocumentStatus status);

    /** Documents uploaded by a specific user (integer FK). */
    Page<Document> findByUploadedByAndStatusNotOrderByCreatedAtDesc(
            Integer uploadedBy, DocumentStatus status, Pageable pageable);
    /** Documents belonging to a specific party (customer). */
    Page<Document> findByPartyExternalIdAndStatusNotOrderByCreatedAtDesc(
            String partyExternalId, DocumentStatus status, Pageable pageable);

    /** Documents that are in a given status but have no category (needs classification). */
    Page<Document> findByStatusAndCategoryIdIsNullOrderByCreatedAtDesc(
            DocumentStatus status, Pageable pageable);

    /** Documents in any of the given statuses (for classification + assignment queues). */
    Page<Document> findByStatusInOrderByCreatedAtDesc(
            List<DocumentStatus> statuses, Pageable pageable);

    /** Auto-classified documents for spot check audit. */
    Page<Document> findByStatusAndClassificationSourceOrderByCreatedAtDesc(
            DocumentStatus status, String classificationSource, Pageable pageable);

    // Search by document name, filename, customer ref, or customer name
    @Query("""
    SELECT d FROM Document d
    WHERE d.status <> :excluded
      AND (LOWER(d.name) LIKE LOWER(CONCAT('%', :q, '%'))
        OR LOWER(d.originalFilename) LIKE LOWER(CONCAT('%', :q, '%'))
        OR LOWER(d.partyExternalId) LIKE LOWER(CONCAT('%', :q, '%')))
    ORDER BY d.createdAt DESC
    """)
    Page<Document> searchByName(
            @Param("q") String query,
            @Param("excluded") DocumentStatus excluded,
            Pageable pageable);

    // ── Retention scheduler queries ──────────────────────────────────────────

    /** ACTIVE docs in a category older than cutoff → eligible for auto-archive */
    List<Document> findByStatusAndCategoryIdAndCreatedAtBefore(
            DocumentStatus status, Integer categoryId, Instant cutoff);

    /** ACTIVE docs with no category older than cutoff */
    List<Document> findByStatusAndCategoryIdIsNullAndCreatedAtBefore(
            DocumentStatus status, Instant cutoff);

    /** ARCHIVED docs in a category archived before cutoff → eligible for purge */
    List<Document> findByStatusAndCategoryIdAndArchivedAtBefore(
            DocumentStatus status, Integer categoryId, Instant cutoff);

    /** ARCHIVED docs with no category archived before cutoff */
    List<Document> findByStatusAndCategoryIdIsNullAndArchivedAtBefore(
            DocumentStatus status, Instant cutoff);

    // ── Version chain queries ───────────────────────────────────────────────

    /** Find all versions that are children of this document. */
    List<Document> findByParentDocIdOrderByVersionAsc(UUID parentDocId);

    /** Find the latest version in a chain (isLatestVersion = true, same parent). */
    Optional<Document> findByParentDocIdAndIsLatestVersionTrue(UUID parentDocId);

    /** Release expired document locks — returns count of released locks */
    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE Document d SET d.lockedBy = null, d.lockedAt = null, d.lockExpiresAt = null " +
           "WHERE d.lockedBy IS NOT NULL AND d.lockExpiresAt <= :now")
    int releaseExpiredLocks(@Param("now") Instant now);
}