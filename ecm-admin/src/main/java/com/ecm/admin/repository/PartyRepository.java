package com.ecm.admin.repository;

import com.ecm.admin.entity.Party;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Read-only repository for ecm_core.parties.
 *
 * All write operations (INSERT / UPDATE / soft-delete) must go through
 * PartyService.create / update / deactivate which use JdbcTemplate to
 * honour the cross-schema write rule.
 *
 * NOTE: The searchActive query intentionally has NO (:q IS NULL OR ...) guard.
 * Hibernate 6 + PostgreSQL passes null bind parameters as bytea (unknown type),
 * causing "function lower(bytea) does not exist".  The null case is handled in
 * PartyService by calling findAllByIsActiveTrue() instead.
 */
public interface PartyRepository extends JpaRepository<Party, UUID> {

    Optional<Party> findByExternalId(String externalId);

    boolean existsByExternalId(String externalId);

    /**
     * Paged list of ALL active parties — used when no search term is provided.
     * Spring Data derives the query automatically; no JPQL needed.
     */
    Page<Party> findAllByIsActiveTrueOrderByDisplayNameAsc(Pageable pageable);

    /**
     * Paged free-text search against display_name OR external_id.
     * Only called when the search term is a non-null, non-blank String.
     * Caller (PartyService) is responsible for never passing null here.
     */
    @Query("""
           SELECT p FROM Party p
           WHERE p.isActive = true
             AND (LOWER(p.displayName) LIKE LOWER(CONCAT('%', :q, '%'))
                  OR LOWER(p.externalId) LIKE LOWER(CONCAT('%', :q, '%')))
           ORDER BY p.displayName ASC
           """)
    Page<Party> searchActive(@Param("q") String q, Pageable pageable);

    /**
     * Lookup by UUID — active records only.
     */
    Optional<Party> findByIdAndIsActiveTrue(UUID id);
}