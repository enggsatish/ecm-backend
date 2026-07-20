package com.ecm.admin.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Tier B of CRM-aware form fill — a one-to-many CRM relationship a customer
 * can have several of (e.g. "Membership", "Account"). salesforceParentField
 * is the field on the Salesforce object that links each record back to the
 * customer's Contact/Account, used to query "all records for this customer".
 *
 * parentObject is the Salesforce object holding the customer's OWN record
 * (Contact for Retail, Account for SMB/Commercial) — salesforceParentField
 * stores a Salesforce Id (it's a lookup), not the customer's external ref
 * string, so resolving that Id is a required first step, not the field the
 * customer's ref is matched against directly.
 */
@Entity
@Table(name = "customer_relationship_types", schema = "ecm_admin")
public class CustomerRelationshipType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Column(name = "salesforce_object", nullable = false, length = 100)
    private String salesforceObject;

    @Column(name = "salesforce_parent_field", nullable = false, length = 150)
    private String salesforceParentField;

    @Column(name = "parent_object", nullable = false, length = 100)
    private String parentObject = "Contact";

    /** Comma-separated segment codes (RETAIL,SMB,COMMERCIAL) this type applies to; null/blank = all. */
    @Column(name = "segments", length = 100)
    private String segments;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @OneToMany(mappedBy = "relationshipType", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<CustomerRelationshipAttribute> attributes = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSalesforceObject() { return salesforceObject; }
    public void setSalesforceObject(String salesforceObject) { this.salesforceObject = salesforceObject; }
    public String getSalesforceParentField() { return salesforceParentField; }
    public void setSalesforceParentField(String salesforceParentField) { this.salesforceParentField = salesforceParentField; }
    public String getParentObject() { return parentObject; }
    public void setParentObject(String parentObject) { this.parentObject = parentObject; }
    public String getSegments() { return segments; }
    public void setSegments(String segments) { this.segments = segments; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
    public List<CustomerRelationshipAttribute> getAttributes() { return attributes; }
    public void setAttributes(List<CustomerRelationshipAttribute> attributes) { this.attributes = attributes; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
