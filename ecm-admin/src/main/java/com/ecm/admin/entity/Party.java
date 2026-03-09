package com.ecm.admin.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Read-only JPA projection of ecm_core.parties.
 *
 * ecm_core is owned by ecm-identity's Flyway — ecm-admin NEVER writes to
 * this table via JPA.  All inserts / updates are performed via JdbcTemplate
 * in PartyService (cross-schema write rule).
 *
 * @Immutable tells Hibernate not to generate UPDATE SQL for this entity.
 */
@Entity
@Immutable
@Table(name = "parties", schema = "ecm_core")
public class Party {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Manually-assigned human-visible identifier, e.g. COMM-001, RET-0042. */
    @Column(name = "external_id", nullable = false, unique = true, length = 100)
    private String externalId;

    /** COMMERCIAL | SMB | RETAIL */
    @Column(name = "party_type", nullable = false, length = 20)
    private String partyType;

    /** Soft FK → ecm_admin.segments.id */
    @Column(name = "segment_id", nullable = false)
    private Integer segmentId;

    @Column(name = "display_name", nullable = false, length = 255)
    private String displayName;

    @Column(name = "short_name", length = 100)
    private String shortName;

    /** Business registration / tax number. */
    @Column(name = "registration_no", length = 100)
    private String registrationNo;

    /** Self-referential FK for COMMERCIAL → SMB hierarchy. */
    @Column(name = "parent_party_id")
    private UUID parentPartyId;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "notes", columnDefinition = "text")
    private String notes;

    /** Entra Object ID of the admin who created this record. */
    @Column(name = "created_by", nullable = false, length = 255)
    private String createdBy;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    // ── Read-only accessors (no setters — @Immutable) ─────────────────────────

    public UUID getId()               { return id; }
    public String getExternalId()     { return externalId; }
    public String getPartyType()      { return partyType; }
    public Integer getSegmentId()     { return segmentId; }
    public String getDisplayName()    { return displayName; }
    public String getShortName()      { return shortName; }
    public String getRegistrationNo() { return registrationNo; }
    public UUID getParentPartyId()    { return parentPartyId; }
    public Boolean getIsActive()      { return isActive; }
    public String getNotes()          { return notes; }
    public String getCreatedBy()      { return createdBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
