package com.ecm.gateway.dto;

import lombok.*;
import java.util.List;

/**
 * Request body sent to ecm-identity POST /internal/auth/enrich.
 *
 * Sprint G-fix: Added oktaGroups so the identity service can detect
 * first-time ECM_ADMIN users on a fresh DB and auto-provision them.
 * Without this, a fresh install is a deadlock: no users → NO_ACCESS → can't log in.
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
     * Okta group memberships from the JWT 'groups' claim.
     * Forwarded to identity so it can bootstrap the first admin user
     * on a fresh database. Null-safe — may be empty if claim absent.
     */
    private List<String> oktaGroups;
}