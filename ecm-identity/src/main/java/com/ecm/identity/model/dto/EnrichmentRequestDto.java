package com.ecm.identity.model.dto;

import lombok.*;
import java.util.List;

/**
 * Request body for POST /internal/auth/enrich.
 * Called exclusively by ecm-gateway's EcmRoleEnrichmentFilter.
 *
 * Sprint G-fix: Added oktaGroups so EnrichmentService can auto-provision
 * the first ECM_ADMIN user on a fresh database (bootstrap deadlock fix).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EnrichmentRequestDto {

    /** JWT sub claim — stored as entra_object_id in ecm_core.users */
    private String sub;

    /** User's email address from the JWT email claim */
    private String email;

    /**
     * Okta group memberships forwarded from the JWT 'groups' claim.
     * Used ONLY for the first-run bootstrap: if no user exists in the DB
     * and this list contains 'ECM_ADMIN', the user is auto-provisioned
     * with the ECM_ADMIN system role and immediately granted access.
     *
     * This is intentionally narrow in scope — it is NOT used for ongoing
     * role assignment. That is always done via the Admin UI.
     */
    private List<String> oktaGroups;
}