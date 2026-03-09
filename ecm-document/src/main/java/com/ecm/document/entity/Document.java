package com.ecm.document.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Maps to ecm_core.documents — schema is owned by the existing Flyway migration.
 * Column names match exactly; do NOT rename without a new migration.
 */
@Entity
@Table(name = "documents", schema = "ecm_core")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Document {

    @Id
    // @GeneratedValue INTENTIONALLY REMOVED.
    // DocumentServiceImpl.upload() generates documentId = UUID.randomUUID() and passes
    // it to Document.builder().id(documentId). Keeping @GeneratedValue causes Hibernate
    // to generate a SECOND UUID at INSERT time, discarding the builder-set value.
    // That makes the publishOcrEvent fallback use a stale ID → "document missing" in OpenSearch.
    @Column(updatable = false, nullable = false)
    private UUID id;

    /** Human-readable display name. */
    @Column(name = "name", nullable = false, length = 500)
    private String name;

    /** Original filename from the upload. */
    @Column(name = "original_filename", nullable = false, length = 500)
    private String originalFilename;

    /** MIME type e.g. application/pdf. */
    @Column(name = "mime_type", nullable = false, length = 100)
    private String mimeType;

    /** File size in bytes. */
    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    /**
     * Full path in blob/object storage.
     * For MinIO: "{bucket}/{year}/{month}/{uuid}/{filename}"
     * Stores the full path so the storage layer can resolve without a bucket column.
     */
    @Column(name = "blob_storage_path", nullable = false, length = 1000)
    private String blobStoragePath;

    /** FK → ecm_core.document_categories.id */
    @Column(name = "category_id")
    private Integer categoryId;

    /** FK → ecm_core.departments.id */
    @Column(name = "department_id")
    private Integer departmentId;

    /** FK → ecm_core.users.id — integer PK of the user who uploaded */
    @Column(name = "uploaded_by")
    private Integer uploadedBy;

    /** Document lifecycle status. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 50)
    @Builder.Default
    private DocumentStatus status = DocumentStatus.PENDING_OCR;

    /** Optimistic locking / version tracking. */
    @Column(name = "version")
    @Builder.Default
    private Integer version = 1;

    /** Points to the previous version of this document. */
    @Column(name = "parent_doc_id")
    private UUID parentDocId;

    /** True if this is the most recent version. */
    @Column(name = "is_latest_version")
    @Builder.Default
    private Boolean isLatestVersion = true;

    /** Set to true once OCR processing is complete. */
    @Column(name = "ocr_completed")
    @Builder.Default
    private Boolean ocrCompleted = false;

    /** Plain text extracted by OCR. */
    @Column(name = "extracted_text", columnDefinition = "text")
    private String extractedText;

    /** Structured fields extracted by OCR (JSON). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "extracted_fields", columnDefinition = "jsonb")
    private String extractedFields;

    /** Arbitrary metadata (JSON). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private String metadata;

    /** Free-form tags stored as a Postgres text array. */
    @Column(name = "tags", columnDefinition = "text[]")
    private String[] tags;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "uploaded_by_email", length = 255)
    private String uploadedByEmail;

    /** Soft ref → ecm_admin.segments.id */
    @Column(name = "segment_id")
    private Integer segmentId;

    /** Soft ref → ecm_admin.product_lines.id */
    @Column(name = "product_line_id")
    private Integer productLineId;

    @Column(name = "party_external_id", length = 100)
    private String partyExternalId;
}