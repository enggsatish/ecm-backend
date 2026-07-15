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
 * Maps to ecm_core.documents — v5.0 schema.
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

    /** UUID-based storage key in MinIO: {tenant}/{uuid}/v{version} */
    @Column(name = "storage_key", nullable = false, length = 500)
    private String storageKey;

    /** Storage bucket name. */
    @Column(name = "storage_bucket", nullable = false, length = 100)
    @Builder.Default
    private String storageBucket = "ecm-documents";

    /** Soft ref → ecm_admin.document_categories.id (no hard FK — cross-schema; integrity owned by application) */
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

    /** SHA-256 checksum of the stored file. */
    @Column(name = "checksum", length = 128)
    private String checksum;

    /** How the document was classified: MANUAL, AUTO_CLASSIFIED, QR_CODE, MIGRATION, BATCH */
    @Column(name = "classification_source", length = 30)
    private String classificationSource;

    /** Classification confidence score (0.00 - 100.00). */
    @Column(name = "classification_confidence")
    private java.math.BigDecimal classificationConfidence;

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

    /** FK → ecm_core.parties.id */
    @Column(name = "party_id")
    private UUID partyId;

    // ── Pipeline state (data-driven visualization) ────────────────────
    /** JSON array of pipeline steps — each service appends its steps. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "pipeline_state", columnDefinition = "jsonb")
    @Builder.Default
    private String pipelineState = "[]";

    // ── Optimistic locking — prevents concurrent update conflicts ──────
    @jakarta.persistence.Version
    @Column(name = "opt_lock_version")
    private Long optLockVersion;

    // ── Document checkout / locking ────────────────────────────────────
    @Column(name = "locked_by", length = 255)
    private String lockedBy;

    @Column(name = "locked_at")
    private Instant lockedAt;

    @Column(name = "lock_expires_at")
    private Instant lockExpiresAt;

    /** Lock type: USER, CASE, or BATCH. */
    @Column(name = "lock_type", length = 20)
    private String lockType;

    // ── Archive / Delete audit fields ──────────────────────────────────
    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "archived_by", length = 255)
    private String archivedBy;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "deleted_by", length = 255)
    private String deletedBy;

    @Column(name = "delete_reason", length = 500)
    private String deleteReason;

    @Column(name = "purged_at")
    private Instant purgedAt;
}