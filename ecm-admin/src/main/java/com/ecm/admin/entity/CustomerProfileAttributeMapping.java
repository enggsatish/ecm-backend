package com.ecm.admin.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.OffsetDateTime;

/**
 * Salesforce binding for one CRM_MAPPED profile attribute (Layer 2). One
 * mapping per attribute in v1 — a single Salesforce object.field per
 * canonical attribute.
 */
@Entity
@Table(name = "customer_profile_attribute_mappings", schema = "ecm_admin")
public class CustomerProfileAttributeMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "attribute_id", nullable = false, unique = true)
    private CustomerProfileAttribute attribute;

    @Column(name = "salesforce_object", nullable = false, length = 100)
    private String salesforceObject;

    @Column(name = "salesforce_field", nullable = false, length = 150)
    private String salesforceField;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public CustomerProfileAttribute getAttribute() { return attribute; }
    public void setAttribute(CustomerProfileAttribute attribute) { this.attribute = attribute; }
    public String getSalesforceObject() { return salesforceObject; }
    public void setSalesforceObject(String salesforceObject) { this.salesforceObject = salesforceObject; }
    public String getSalesforceField() { return salesforceField; }
    public void setSalesforceField(String salesforceField) { this.salesforceField = salesforceField; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
