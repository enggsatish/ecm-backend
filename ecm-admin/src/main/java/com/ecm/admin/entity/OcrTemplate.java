package com.ecm.admin.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;
import java.time.OffsetDateTime;

/**
 * DB-stored OCR extraction template. One template per document category.
 * Fields column stores the extraction rules as JSONB:
 * [{fieldName, pattern, defaultValue}, ...]
 */
@Entity
@Table(name = "ocr_templates", schema = "ecm_admin")
public class OcrTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "category_id", unique = true)
    private Integer categoryId;

    @Column(name = "category_code", nullable = false, unique = true, length = 100)
    private String categoryCode;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private String fields = "[]";

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "created_by", length = 255)
    private String createdBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    // ── Getters / Setters ─────────────────────────────────────────────────

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public Integer getCategoryId() { return categoryId; }
    public void setCategoryId(Integer categoryId) { this.categoryId = categoryId; }
    public String getCategoryCode() { return categoryCode; }
    public void setCategoryCode(String categoryCode) { this.categoryCode = categoryCode; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getFields() { return fields; }
    public void setFields(String fields) { this.fields = fields; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
