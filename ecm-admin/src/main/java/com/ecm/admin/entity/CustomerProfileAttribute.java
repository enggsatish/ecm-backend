package com.ecm.admin.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.OffsetDateTime;

/**
 * Superadmin-defined customer profile attribute — canonical vocabulary that
 * forms bind to (Layer 1 of CRM-aware form fill). source=CRM_MAPPED attributes
 * carry a matching {@link CustomerProfileAttributeMapping}; MANUAL ones are
 * edited directly wherever the customer profile is shown.
 */
@Entity
@Table(name = "customer_profile_attributes", schema = "ecm_admin")
public class CustomerProfileAttribute {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false, unique = true, length = 100)
    private String key;

    @Column(nullable = false, length = 200)
    private String label;

    @Column(name = "value_type", nullable = false, length = 30)
    private String valueType = "STRING";

    @Column(nullable = false, length = 20)
    private String source = "MANUAL";

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    /** Comma-separated segment codes (RETAIL,SMB,COMMERCIAL) this attribute applies to; null/blank = all. */
    @Column(name = "segments", length = 100)
    private String segments;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getValueType() { return valueType; }
    public void setValueType(String valueType) { this.valueType = valueType; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public String getSegments() { return segments; }
    public void setSegments(String segments) { this.segments = segments; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
