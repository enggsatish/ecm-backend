package com.ecm.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Inbound DTO for POST /api/admin/customers (create)
 * and PUT /api/admin/customers/:id (update).
 *
 * customerRef is only used on create — it maps to external_id and is
 * immutable once set (the DB UNIQUE constraint enforces this).
 *
 * segment must be one of: RETAIL | SMB | COMMERCIAL
 * (matches party_type CHECK constraint in ecm_core.parties).
 *
 * segmentId is the soft FK to ecm_admin.segments.id.  Frontend may omit it
 * (older builds); backend defaults to 0 if not supplied so the NOT NULL
 * DB constraint is satisfied.  Admin should map segments in a follow-up.
 */
public record PartyRequest(

        /** Required on create; ignored on update (path variable is the key). */
        @NotBlank(message = "customerRef is required")
        @Size(max = 100, message = "customerRef must be ≤ 100 characters")
        String customerRef,

        @NotBlank(message = "displayName is required")
        @Size(max = 255, message = "displayName must be ≤ 255 characters")
        String displayName,

        /** RETAIL | SMB | COMMERCIAL */
        @NotBlank(message = "segment is required")
        @Pattern(regexp = "RETAIL|SMB|COMMERCIAL",
                 message = "segment must be RETAIL, SMB, or COMMERCIAL")
        String segment,

        /** Soft FK → ecm_admin.segments.id. Defaults to 0 if omitted. */
        Integer segmentId,

        @Size(max = 100, message = "shortName must be ≤ 100 characters")
        String shortName,

        @Size(max = 100, message = "registrationNo must be ≤ 100 characters")
        String registrationNo,

        /** Self-referential: COMMERCIAL parent of an SMB party. */
        UUID parentPartyId,

        String notes,

        /* ── Contact fields (accepted from frontend but not persisted in
           ecm_core.parties — future sprint will add a contacts table).
           Included here so validation does not reject the payload. ── */
        String email,
        String phone,

        /** Accepted but ignored on create; only relevant for update filtering. */
        String primaryProductId,
        String primaryProductName
) {}
