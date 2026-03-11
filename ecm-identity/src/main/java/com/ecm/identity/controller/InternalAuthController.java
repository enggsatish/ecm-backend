package com.ecm.identity.controller;

import com.ecm.identity.model.dto.EnrichmentRequestDto;
import com.ecm.identity.model.dto.EnrichmentResponseDto;
import com.ecm.identity.service.EnrichmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Internal service-to-service controller for ecm-gateway enrichment calls.
 *
 * SECURITY NOTE:
 * This endpoint is NOT JWT-protected (see SecurityConfig — /internal/** is permitAll).
 * It is only reachable from within the internal network via the gateway.
 * The gateway's routing rules MUST NOT expose /internal/** to external clients.
 *
 * Production hardening: restrict access by source IP or mTLS in the gateway
 * routing configuration.
 */
@Slf4j
@RestController
@RequestMapping("/internal/auth")
@RequiredArgsConstructor
public class InternalAuthController {

    private final EnrichmentService enrichmentService;

    /**
     * POST /internal/auth/enrich
     *
     * Called by EcmRoleEnrichmentFilter in ecm-gateway on Redis cache miss.
     * Returns roles and permissions for the given Okta subject.
     *
     * Response:
     *   200 + { status:"OK", userId, roles[], permissions[], cachedAt }
     *   200 + { status:"NO_ACCESS", roles:[], permissions:[] }  — no roles assigned
     */
    @PostMapping("/enrich")
    public ResponseEntity<EnrichmentResponseDto> enrich(
            @Valid @RequestBody EnrichmentRequestDto request) {

        log.debug("Enrichment request for sub={}", request.getSub());

        EnrichmentResponseDto response =
                enrichmentService.enrich(request.getSub(), request.getEmail());

        return ResponseEntity.ok(response);
    }
}
