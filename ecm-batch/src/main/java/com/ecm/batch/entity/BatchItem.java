package com.ecm.batch.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "batch_items", schema = "ecm_batch")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BatchItem {

    @Id
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batch_id", nullable = false)
    private BatchJob batchJob;

    @Column(name = "document_id")
    private UUID documentId;

    @Column(name = "original_filename", length = 500)
    private String originalFilename;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 30, nullable = false)
    @Builder.Default
    private BatchItemStatus status = BatchItemStatus.QUEUED;

    // ── Auto-classification results ──────────────────────────────────────────

    @Column(name = "detected_category_id")
    private Integer detectedCategoryId;

    @Column(name = "category_confidence", precision = 5, scale = 2)
    private BigDecimal categoryConfidence;

    @Column(name = "detected_customer_id")
    private UUID detectedCustomerId;

    @Column(name = "customer_confidence", precision = 5, scale = 2)
    private BigDecimal customerConfidence;

    // ── Extracted fields ─────────────────────────────────────────────────────

    @Column(name = "extracted_name", length = 255)
    private String extractedName;

    @Column(name = "extracted_account_no", length = 50)
    private String extractedAccountNo;

    @Column(name = "extracted_dob")
    private LocalDate extractedDob;

    @Column(name = "extracted_address", length = 500)
    private String extractedAddress;

    /** JSON array of candidate customer UUIDs for review */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "candidate_customer_ids", columnDefinition = "jsonb")
    private String candidateCustomerIds;

    // ── Review fields ────────────────────────────────────────────────────────

    @Column(name = "reviewed_by", length = 255)
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "final_category_id")
    private Integer finalCategoryId;

    @Column(name = "final_customer_id")
    private UUID finalCustomerId;

    @Column(name = "review_notes", columnDefinition = "text")
    private String reviewNotes;

    // ── Error tracking ───────────────────────────────────────────────────────

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
