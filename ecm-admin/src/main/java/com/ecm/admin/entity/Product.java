package com.ecm.admin.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "products", schema = "ecm_admin")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "product_code", nullable = false, unique = true, length = 50)
    private String productCode;

    @Column(name = "display_name", nullable = false, length = 200)
    private String displayName;

    @Column(columnDefinition = "TEXT")
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "product_schema", columnDefinition = "jsonb")
    private String productSchema;

    @Column(name = "case_workflow_key", length = 100)
    private String caseWorkflowKey;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<ProductDocumentType> documentTypes = new ArrayList<>();

    @Column(name = "product_line_id")
    private Integer productLineId;

    @Column(name = "segment_id")
    private Integer segmentId;

    // ── Getters / Setters ─────────────────────────────────────────────────

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getProductCode() { return productCode; }
    public void setProductCode(String productCode) { this.productCode = productCode; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getProductSchema() { return productSchema; }
    public void setProductSchema(String productSchema) { this.productSchema = productSchema; }
    public String getCaseWorkflowKey() { return caseWorkflowKey; }
    public void setCaseWorkflowKey(String caseWorkflowKey) { this.caseWorkflowKey = caseWorkflowKey; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
    public List<ProductDocumentType> getDocumentTypes() { return documentTypes; }
    public void setDocumentTypes(List<ProductDocumentType> documentTypes) { this.documentTypes = documentTypes; }
    public Integer getProductLineId() { return productLineId; }
    public void setProductLineId(Integer productLineId) { this.productLineId = productLineId; }
    public Integer getSegmentId() { return segmentId; }
    public void setSegmentId(Integer segmentId) { this.segmentId = segmentId; }
}
