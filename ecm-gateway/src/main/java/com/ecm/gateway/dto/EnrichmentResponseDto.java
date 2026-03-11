package com.ecm.gateway.dto;

import lombok.*;

import java.util.Collections;
import java.util.List;

/**
 * Response from ecm-identity POST /internal/auth/enrich.
 * Deserialized by IdentityEnrichmentClient, used by EcmRoleEnrichmentFilter.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EnrichmentResponseDto {

    private String       status;      // "OK" or "NO_ACCESS"
    private Integer      userId;
    private List<String> roles;
    private List<String> permissions;
    private String       cachedAt;

    public static EnrichmentResponseDto noAccess() {
        return EnrichmentResponseDto.builder()
                .status("NO_ACCESS")
                .roles(Collections.emptyList())
                .permissions(Collections.emptyList())
                .build();
    }
}
