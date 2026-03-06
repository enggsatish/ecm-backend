package com.ecm.eforms.model.entity;

import com.ecm.eforms.model.schema.FormSchema;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Maps to ecm_forms.form_submissions.
 *
 * Hibernate 6 / Spring Boot 3 JSONB mapping:
 *   @TypeDef was REMOVED in Hibernate 6 — use @JdbcTypeCode(SqlTypes.JSON) instead.
 *   hypersistence-utils JsonBinaryType is still needed for PostgreSQL JSONB
 *   serialisation/deserialisation but is no longer registered via @TypeDef.
 *
 * Status flow:
 *   DRAFT → SUBMITTED → PENDING_SIGNATURE → SIGNED → IN_REVIEW → APPROVED | REJECTED → COMPLETED
 *                                         ↘ SIGN_DECLINED
 *   Any non-terminal state → WITHDRAWN (by submitter)
 */
@Entity
@Table(schema = "ecm_forms", name = "form_submissions")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FormSubmission {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    @Builder.Default
    private String tenantId = "default";

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "form_definition_id", nullable = false)
    private FormDefinition formDefinition;

    @Column(name = "form_key", nullable = false)
    private String formKey;

    @Column(name = "form_version", nullable = false)
    private Integer formVersion;

    /** Point-in-time schema snapshot — NEVER updated after submission */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "form_schema_snapshot", columnDefinition = "jsonb")
    private FormSchema formSchemaSnapshot;

    /** The actual filled-in values: { "fieldKey": value, ... } */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "submission_data", columnDefinition = "jsonb")
    private Map<String, Object> submissionData;

    @Column(nullable = false)
    @Builder.Default
    private String status = "DRAFT";

    // ── Submitter ─────────────────────────────────────────────────────

    @Column(name = "submitted_by", nullable = false)
    private String submittedBy;

    @Column(name = "submitted_by_name")
    private String submittedByName;

    @Column(name = "submitted_at")
    private OffsetDateTime submittedAt;

    // ── DocuSign tracking ─────────────────────────────────────────────

    @Column(name = "docusign_envelope_id")
    private String docuSignEnvelopeId;

    @Column(name = "docusign_status")
    private String docuSignStatus;

    @Column(name = "docusign_sent_at")
    private OffsetDateTime docuSignSentAt;

    @Column(name = "docusign_completed_at")
    private OffsetDateTime docuSignCompletedAt;

    /** MinIO UUID of the completed signed PDF downloaded from DocuSign */
    @Column(name = "signed_document_id")
    private UUID signedDocumentId;

    /** MinIO UUID of the draft PDF generated before signing */
    @Column(name = "draft_document_id")
    private UUID draftDocumentId;

    // ── Workflow ──────────────────────────────────────────────────────

    @Column(name = "workflow_instance_id")
    private String workflowInstanceId;

    // ── Backoffice review ─────────────────────────────────────────────

    @Column(name = "assigned_to")
    private String assignedTo;

    @Column(name = "assigned_at")
    private OffsetDateTime assignedAt;

    @Column(name = "review_notes", columnDefinition = "TEXT")
    private String reviewNotes;

    @Column(name = "reviewed_by")
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private OffsetDateTime reviewedAt;

    // ── Request metadata ──────────────────────────────────────────────

    @Column(nullable = false)
    @Builder.Default
    private String channel = "WEB";

    @Column(name = "ip_address")
    private String ipAddress;

    @Column(name = "user_agent", columnDefinition = "TEXT")
    private String userAgent;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    // ── Lifecycle helpers ─────────────────────────────────────────────

    public void markSubmitted(String userId, String userName) {
        this.status          = "SUBMITTED";
        this.submittedBy     = userId;
        this.submittedByName = userName;
        this.submittedAt     = OffsetDateTime.now();
    }

    public void markPendingSignature(String envelopeId) {
        this.status             = "PENDING_SIGNATURE";
        this.docuSignEnvelopeId = envelopeId;
        this.docuSignStatus     = "sent";
        this.docuSignSentAt     = OffsetDateTime.now();
    }

    public void markSigned(UUID signedDocId) {
        this.status              = "SIGNED";
        this.docuSignStatus      = "completed";
        this.docuSignCompletedAt = OffsetDateTime.now();
        this.signedDocumentId    = signedDocId;
    }

    public void assignForReview(String reviewerId) {
        this.status     = "IN_REVIEW";
        this.assignedTo = reviewerId;
        this.assignedAt = OffsetDateTime.now();
    }
}