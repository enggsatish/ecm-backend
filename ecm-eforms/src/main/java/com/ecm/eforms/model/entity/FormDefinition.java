package com.ecm.eforms.model.entity;

import com.ecm.eforms.model.schema.*;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Maps to ecm_forms.form_definitions.
 */
@Entity
@Table(schema = "ecm_forms", name = "form_definitions")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FormDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    @Builder.Default
    private String tenantId = "default";

    @Column(name = "form_key", nullable = false)
    private String formKey;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "product_type_code")
    private String productTypeCode;

    @Column(name = "form_type_code")
    private String formTypeCode;

    @Column(nullable = false)
    @Builder.Default
    private Integer version = 1;

    /**
     * PostgreSQL TEXT[] column — maps directly to the array column on the table row.
     *
     * WHY NOT @ElementCollection:
     *   @ElementCollection creates ecm_forms.form_definitions_tags as a JOIN table.
     *   The actual column  form_definitions.tags (TEXT[]) stays unmapped → Hibernate
     *   then throws "column tags does not exist" on INSERT or validation failure.
     *
     * Hibernate 6 native array support:
     *   SqlTypes.ARRAY tells Hibernate to use the JDBC Array type.
     *   columnDefinition="text[]" ensures Flyway validate passes (exact DDL match).
     */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "tags", columnDefinition = "text[]")
    private List<String> tags;

    @Column(nullable = false)
    @Builder.Default
    private String status = "DRAFT";

    // ── JSONB columns ─────────────────────────────────────────────────

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private FormSchema schema;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ui_config", columnDefinition = "jsonb")
    private Map<String, Object> uiConfig;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "workflow_config", columnDefinition = "jsonb")
    private WorkflowConfig workflowConfig;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "docusign_config", columnDefinition = "jsonb")
    private DocuSignFormConfig docuSignConfig;

    @Column(name = "document_template_id")
    private UUID documentTemplateId;

    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    @Column(name = "published_by")
    private String publishedBy;

    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;

    @Column(name = "archived_by")
    private String archivedBy;

    @Column(name = "created_by", nullable = false, updatable = false)
    private String createdBy;

    @Column(name = "updated_by")
    private String updatedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    // ── Lifecycle helpers ─────────────────────────────────────────────

    public void publish(String userId) {
        if (!"DRAFT".equals(this.status))
            throw new IllegalStateException("Only DRAFT forms can be published. Status: " + this.status);
        this.status      = "PUBLISHED";
        this.publishedAt = OffsetDateTime.now();
        this.publishedBy = userId;
        this.updatedBy   = userId;
    }

    public void archive(String userId) {
        if (!"PUBLISHED".equals(this.status))
            throw new IllegalStateException("Only PUBLISHED forms can be archived. Status: " + this.status);
        this.status     = "ARCHIVED";
        this.archivedAt = OffsetDateTime.now();
        this.archivedBy = userId;
        this.updatedBy  = userId;
    }

    public boolean isDraft()     { return "DRAFT".equals(this.status); }
    public boolean isPublished() { return "PUBLISHED".equals(this.status); }
    public boolean isArchived()  { return "ARCHIVED".equals(this.status); }
}
