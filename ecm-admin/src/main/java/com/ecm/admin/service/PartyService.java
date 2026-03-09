package com.ecm.admin.service;

import com.ecm.admin.dto.PartyDto;
import com.ecm.admin.dto.PartyRequest;
import com.ecm.admin.repository.PartyRepository;
import com.ecm.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Business logic for Party (Customer) management.
 *
 * READ operations use JPA via PartyRepository (ecm-admin reads ecm_core.parties).
 * WRITE operations use JdbcTemplate — ecm_core is owned by ecm-identity's Flyway;
 * ecm-admin must not write to it via JPA (cross-schema write rule).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PartyService {

    private final PartyRepository partyRepo;
    private final JdbcTemplate    jdbc;

    // ── List / Search ─────────────────────────────────────────────────────────

    /**
     * Returns a page of active parties, optionally filtered by a free-text query.
     *
     * We deliberately split the null / non-null path into two separate repository
     * calls.  Hibernate 6 passes null bind parameters as PostgreSQL bytea (unknown
     * type), so a single JPQL query with (:q IS NULL OR lower(...) LIKE lower(...))
     * fails with "function lower(bytea) does not exist" whenever q is null.
     */
    @Transactional(readOnly = true)
    public Page<PartyDto> search(String q, int page, int size) {
        String normalised = (q == null || q.isBlank()) ? null : q.trim();
        PageRequest pageable = PageRequest.of(page, Math.min(size, 100));

        if (normalised == null) {
            // No search term — return all active parties ordered by display name.
            return partyRepo.findAllByIsActiveTrueOrderByDisplayNameAsc(pageable)
                    .map(PartyDto::from);
        } else {
            // Non-null term — use the LOWER() LIKE query (safe, no null bind issue).
            return partyRepo.searchActive(normalised, pageable)
                    .map(PartyDto::from);
        }
    }

    // ── Get by ID ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PartyDto getById(UUID id) {
        return partyRepo.findByIdAndIsActiveTrue(id)
                .map(PartyDto::from)
                .orElseThrow(() -> new ResourceNotFoundException("Party not found: " + id));
    }

    // ── Create ────────────────────────────────────────────────────────────────

    /**
     * Insert a new row into ecm_core.parties via JdbcTemplate.
     *
     * @param req      validated request body
     * @param actorId  Entra Object ID of the admin performing the action
     */
    @Transactional
    public PartyDto create(PartyRequest req, String actorId) {
        if (partyRepo.existsByExternalId(req.customerRef())) {
            throw new IllegalArgumentException(
                    "A party with customerRef '" + req.customerRef() + "' already exists");
        }

        UUID newId = UUID.randomUUID();
        int segId  = req.segmentId() != null ? req.segmentId() : 0;

        try {
            jdbc.update("""
                    INSERT INTO ecm_core.parties
                        (id, external_id, party_type, segment_id, display_name,
                         short_name, registration_no, parent_party_id, notes,
                         is_active, created_by, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?,  ?, ?, ?::uuid, ?,  true, ?, NOW(), NOW())
                    """,
                    newId,
                    req.customerRef(),
                    req.segment(),
                    segId,
                    req.displayName(),
                    req.shortName(),
                    req.registrationNo(),
                    req.parentPartyId() != null ? req.parentPartyId().toString() : null,
                    req.notes(),
                    actorId
            );
        } catch (DataIntegrityViolationException ex) {
            log.warn("Create party failed — constraint violation: {}", ex.getMessage());
            throw new IllegalArgumentException("Party creation failed: duplicate key or constraint violation");
        }

        log.info("Created party externalId={} type={} by={}", req.customerRef(), req.segment(), actorId);
        return partyRepo.findById(newId)
                .map(PartyDto::from)
                .orElseThrow(() -> new IllegalStateException("Party not found after insert: " + newId));
    }

    // ── Update ────────────────────────────────────────────────────────────────

    /**
     * Update mutable fields of ecm_core.parties via JdbcTemplate.
     * external_id (customerRef) is immutable after creation.
     */
    @Transactional
    public PartyDto update(UUID id, PartyRequest req) {
        partyRepo.findByIdAndIsActiveTrue(id)
                .orElseThrow(() -> new ResourceNotFoundException("Party not found: " + id));

        int segId = req.segmentId() != null ? req.segmentId() : 0;

        jdbc.update("""
                UPDATE ecm_core.parties
                SET
                    party_type      = ?,
                    segment_id      = ?,
                    display_name    = ?,
                    short_name      = ?,
                    registration_no = ?,
                    parent_party_id = ?::uuid,
                    notes           = ?,
                    updated_at      = NOW()
                WHERE id = ?
                """,
                req.segment(),
                segId,
                req.displayName(),
                req.shortName(),
                req.registrationNo(),
                req.parentPartyId() != null ? req.parentPartyId().toString() : null,
                req.notes(),
                id
        );

        log.info("Updated party id={}", id);
        return partyRepo.findById(id)
                .map(PartyDto::from)
                .orElseThrow(() -> new IllegalStateException("Party not found after update: " + id));
    }

    // ── Deactivate (soft delete) ──────────────────────────────────────────────

    /**
     * Soft-delete: sets is_active = false.  No hard deletes ever.
     */
    @Transactional
    public void deactivate(UUID id) {
        partyRepo.findByIdAndIsActiveTrue(id)
                .orElseThrow(() -> new ResourceNotFoundException("Party not found: " + id));

        jdbc.update("""
                UPDATE ecm_core.parties
                SET is_active = false, updated_at = NOW()
                WHERE id = ?
                """, id);

        log.info("Deactivated party id={}", id);
    }
}