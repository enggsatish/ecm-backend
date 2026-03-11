package com.ecm.gateway.dto;

import lombok.*;

/**
 * Request body sent to ecm-identity POST /internal/auth/enrich.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EnrichmentRequestDto {
    private String sub;
    private String email;
}
