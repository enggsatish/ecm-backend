package com.ecm.admin.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.OffsetDateTime;
import java.util.UUID;

/** A manually-entered value for one MANUAL-source profile attribute, for one customer. */
@Entity
@Table(name = "customer_profile_values", schema = "ecm_admin",
       uniqueConstraints = @UniqueConstraint(columnNames = {"party_id", "attribute_id"}))
public class CustomerProfileValue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "party_id", nullable = false)
    private UUID partyId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "attribute_id", nullable = false)
    private CustomerProfileAttribute attribute;

    @Column(columnDefinition = "TEXT")
    private String value;

    @Column(name = "updated_by", length = 255)
    private String updatedBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public UUID getPartyId() { return partyId; }
    public void setPartyId(UUID partyId) { this.partyId = partyId; }
    public CustomerProfileAttribute getAttribute() { return attribute; }
    public void setAttribute(CustomerProfileAttribute attribute) { this.attribute = attribute; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
