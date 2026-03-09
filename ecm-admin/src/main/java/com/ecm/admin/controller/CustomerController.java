package com.ecm.admin.controller;

import com.ecm.admin.dto.PartyDto;
import com.ecm.admin.dto.PartyRequest;
import com.ecm.admin.service.PartyService;
import com.ecm.common.model.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Customer (Party) management endpoints.
 *
 * Route prefix: /api/admin/customers
 * Access:
 *   ECM_ADMIN       — full CRUD
 *   ECM_BACKOFFICE  — read + create (no delete)
 *
 * "Customer" is the UI term; the domain model uses "Party" to be precise
 * about the COMMERCIAL/SMB/RETAIL type hierarchy used in financial services.
 *
 * All writes flow through PartyService which uses JdbcTemplate (not JPA)
 * because ecm_core is owned by ecm-identity's Flyway migrations.
 */
@RestController
@RequestMapping("/api/admin/customers")
@RequiredArgsConstructor
@Slf4j
public class CustomerController {

    private final PartyService partyService;

    // ── List / Search ─────────────────────────────────────────────────────────

    /**
     * GET /api/admin/customers?q=&page=0&size=20
     * Returns active parties, sorted by displayName.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('ECM_ADMIN', 'ECM_BACKOFFICE')")
    public ResponseEntity<ApiResponse<Page<PartyDto>>> list(
            @RequestParam(required = false)       String q,
            @RequestParam(defaultValue = "0")     int    page,
            @RequestParam(defaultValue = "20")    int    size
    ) {
        log.info(q,page,size);
        Page<PartyDto> result = partyService.search(q, page, size);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ── Get by ID ─────────────────────────────────────────────────────────────

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ECM_ADMIN', 'ECM_BACKOFFICE')")
    public ResponseEntity<ApiResponse<PartyDto>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(partyService.getById(id)));
    }

    // ── Create ────────────────────────────────────────────────────────────────

    @PostMapping
    @PreAuthorize("hasAnyRole('ECM_ADMIN', 'ECM_BACKOFFICE')")
    public ResponseEntity<ApiResponse<PartyDto>> create(
            @Valid @RequestBody  PartyRequest req,
            @AuthenticationPrincipal Jwt      jwt
    ) {
        String actorId = jwt.getSubject();   // Entra Object ID
        log.info("Create party: ref={} type={} by={}", req.customerRef(), req.segment(), actorId);
        PartyDto created = partyService.create(req, actorId);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.ok(created, "Customer created successfully"));
    }

    // ── Update ────────────────────────────────────────────────────────────────

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ECM_ADMIN')")
    public ResponseEntity<ApiResponse<PartyDto>> update(
            @PathVariable                UUID         id,
            @Valid @RequestBody          PartyRequest req,
            @AuthenticationPrincipal     Jwt          jwt
    ) {
        log.info("Update party id={} by={}", id, jwt.getSubject());
        PartyDto updated = partyService.update(id, req);
        return ResponseEntity.ok(ApiResponse.ok(updated, "Customer updated successfully"));
    }

    // ── Deactivate ────────────────────────────────────────────────────────────

    /**
     * Soft-delete — sets is_active = false.
     * No hard deletes in ECM.
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ECM_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deactivate(
            @PathVariable                UUID id,
            @AuthenticationPrincipal     Jwt  jwt
    ) {
        log.info("Deactivate party id={} by={}", id, jwt.getSubject());
        partyService.deactivate(id);
        return ResponseEntity.ok(ApiResponse.ok(null, "Customer deactivated"));
    }
}
