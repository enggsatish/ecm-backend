package com.ecm.identity.model.dto;

import lombok.*;

/**
 * Request body for POST /internal/auth/enrich.
 * Called exclusively by ecm-gateway's EcmRoleEnrichmentFilter.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EnrichmentRequestDto {

    /** JWT sub claim — stored as entra_object_id in ecm_core.users */
    private String sub;

    /** User's email address from the JWT */
    private String email;
}
