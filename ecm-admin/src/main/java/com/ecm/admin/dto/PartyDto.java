package com.ecm.admin.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Outbound DTO returned by CustomerController.
 *
 * Field naming matches what CustomerManagementPage.jsx expects:
 *   id             → UUID primary key (used for PUT / DELETE path variable)
 *   customerRef    → external_id (human-visible, immutable after create)
 *   displayName    → display_name
 *   segment        → party_type (RETAIL | SMB | COMMERCIAL)
 *   segmentId      → soft FK to ecm_admin.segments.id
 *   shortName      → short_name
 *   registrationNo → business registration / tax number
 *   notes          → free-text notes
 *   isActive       → soft-delete flag
 *   createdAt      → audit timestamp
 *
 * email / phone are not stored on the party record (no DB columns).
 * Contact data will be managed in a later sprint.
 */
public record PartyDto(
        UUID            id,
        String          customerRef,        // external_id
        String          displayName,
        String          segment,            // party_type
        Integer         segmentId,
        String          shortName,
        String          registrationNo,
        UUID            parentPartyId,
        String          notes,
        Boolean         isActive,
        OffsetDateTime  createdAt,
        OffsetDateTime  updatedAt
) {
    /** Map from entity → DTO. */
    public static PartyDto from(com.ecm.admin.entity.Party p) {
        return new PartyDto(
                p.getId(),
                p.getExternalId(),
                p.getDisplayName(),
                p.getPartyType(),
                p.getSegmentId(),
                p.getShortName(),
                p.getRegistrationNo(),
                p.getParentPartyId(),
                p.getNotes(),
                p.getIsActive(),
                p.getCreatedAt(),
                p.getUpdatedAt()
        );
    }
}
