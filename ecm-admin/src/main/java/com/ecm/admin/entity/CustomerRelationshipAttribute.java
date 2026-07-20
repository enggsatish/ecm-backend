package com.ecm.admin.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.OffsetDateTime;

/** One attribute within a {@link CustomerRelationshipType}'s own schema (e.g. Membership.membershipNumber). */
@Entity
@Table(name = "customer_relationship_attributes", schema = "ecm_admin",
       uniqueConstraints = @UniqueConstraint(columnNames = {"relationship_type_id", "key"}))
public class CustomerRelationshipAttribute {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "relationship_type_id", nullable = false)
    private CustomerRelationshipType relationshipType;

    @Column(nullable = false, length = 100)
    private String key;

    @Column(nullable = false, length = 200)
    private String label;

    @Column(name = "value_type", nullable = false, length = 30)
    private String valueType = "STRING";

    @Column(name = "salesforce_field", nullable = false, length = 150)
    private String salesforceField;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public CustomerRelationshipType getRelationshipType() { return relationshipType; }
    public void setRelationshipType(CustomerRelationshipType relationshipType) { this.relationshipType = relationshipType; }
    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getValueType() { return valueType; }
    public void setValueType(String valueType) { this.valueType = valueType; }
    public String getSalesforceField() { return salesforceField; }
    public void setSalesforceField(String salesforceField) { this.salesforceField = salesforceField; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
