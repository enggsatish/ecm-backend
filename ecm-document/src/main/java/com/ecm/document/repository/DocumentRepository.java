package com.ecm.document.repository;

import com.ecm.document.entity.Document;
import com.ecm.document.entity.DocumentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
    // Full-text search on name or original_filename (case-insensitive)
    @Query("""
    SELECT d FROM Document d
    WHERE d.status <> :excluded
      AND (LOWER(d.name) LIKE LOWER(CONCAT('%', :q, '%'))
        OR LOWER(d.originalFilename) LIKE LOWER(CONCAT('%', :q, '%')))
    ORDER BY d.createdAt DESC
    """)
    Page<Document> searchByName(
            @Param("q") String query,
            @Param("excluded") DocumentStatus excluded,
            Pageable pageable);
}