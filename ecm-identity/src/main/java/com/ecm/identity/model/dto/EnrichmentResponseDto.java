package com.ecm.identity.model.dto;

import lombok.*;

import java.util.Collections;
import java.util.List;

/**
 * Response shape for POST /internal/auth/enrich.
 * Cached in Redis under ecm:user:enrich:{sub} with 15-minute TTL.
 *
 * status values:
 *   "OK"        — user found, roles and permissions populated
 *   "NO_ACCESS" — user not found or has no roles; gateway returns 403
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EnrichmentResponseDto {

    private String       status;      // "OK" or "NO_ACCESS"
    private Integer      userId;      // ecm_core.users.id
    private List<String> roles;       // e.g. ["ECM_BACKOFFICE", "ECM_REVIEWER"]
    private List<String> permissions; // e.g. ["documents:read", "workflow:approve"]
    private String       cachedAt;    // ISO-8601 timestamp when cached

    /**
     * Factory method for the "user not found or no roles assigned" case.
     * The gateway converts this into a 403 ECM_NO_ACCESS response.
     */
    public static EnrichmentResponseDto noAccess() {
        return EnrichmentResponseDto.builder()
                .status("NO_ACCESS")
                .roles(Collections.emptyList())
                .permissions(Collections.emptyList())
                .build();
    }
}
