package com.ecm.admin.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.OffsetDateTime;

@Entity
@Table(name = "retention_policies", schema = "ecm_admin")
public class RetentionPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false, length = 200)
    private String name;

    /** Soft ref to document_categories.id */
    @Column(name = "category_id")
    private Integer categoryId;

    /** Soft ref to products.product_code */
    @Column(name = "product_code", length = 50)
    private String productCode;

    @Column(name = "archive_after_days", nullable = false)
    private Integer archiveAfterDays = 365;

    @Column(name = "purge_after_days", nullable = false)
    private Integer purgeAfterDays = 2555;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Integer getCategoryId() { return categoryId; }
    public void setCategoryId(Integer categoryId) { this.categoryId = categoryId; }
    public String getProductCode() { return productCode; }
    public void setProductCode(String productCode) { this.productCode = productCode; }
    public Integer getArchiveAfterDays() { return archiveAfterDays; }
    public void setArchiveAfterDays(Integer archiveAfterDays) { this.archiveAfterDays = archiveAfterDays; }
    public Integer getPurgeAfterDays() { return purgeAfterDays; }
    public void setPurgeAfterDays(Integer purgeAfterDays) { this.purgeAfterDays = purgeAfterDays; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
