package com.ecm.admin.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Defines a document type required (or accepted) for a product.
 * Replaces product_category_links with a richer document checklist model.
 *
 * source_type: EFORM (system generates via eForms) | UPLOAD (customer/user provides)
 * on_upload_action: OCR_ONLY (auto-process) | REVIEW_REQUIRED (needs human review first)
 */
@Entity
@Table(name = "product_document_types", schema = "ecm_admin",
       uniqueConstraints = @UniqueConstraint(columnNames = {"product_id", "code"}))
public class ProductDocumentType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private DocumentCategory category;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, length = 100)
    private String code;

    @Column(name = "source_type", nullable = false, length = 20)
    private String sourceType = "UPLOAD";

    /** Soft ref to ecm_forms.form_definitions.id — only when sourceType = EFORM */
    @Column(name = "form_definition_id")
    private UUID formDefinitionId;

    @Column(name = "on_upload_action", nullable = false, length = 30)
    private String onUploadAction = "OCR_ONLY";

    @Column(name = "is_required", nullable = false)
    private Boolean isRequired = true;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    // ── Getters / Setters ─────────────────────────────────────────────────

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public Product getProduct() { return product; }
    public void setProduct(Product product) { this.product = product; }
    public DocumentCategory getCategory() { return category; }
    public void setCategory(DocumentCategory category) { this.category = category; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public UUID getFormDefinitionId() { return formDefinitionId; }
    public void setFormDefinitionId(UUID formDefinitionId) { this.formDefinitionId = formDefinitionId; }
    public String getOnUploadAction() { return onUploadAction; }
    public void setOnUploadAction(String onUploadAction) { this.onUploadAction = onUploadAction; }
    public Boolean getIsRequired() { return isRequired; }
    public void setIsRequired(Boolean isRequired) { this.isRequired = isRequired; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
