package com.ecm.identity.repository;

import com.ecm.identity.model.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Integer> {

    Optional<User> findByEntraObjectId(String entraObjectId);

    /**
     * Sprint G+: Used by EnrichmentService to detect admin-invited pending users.
     *
     * An invited user has:
     *   - email matching the SSO login
     *   - entra_object_id IS NULL  (not yet bound — first login)
     *   - is_active = false        (pending activation)
     *
     * If found, EnrichmentService activates the account and binds the sub.
     * JOIN FETCH roles so we can build the enrichment response immediately
     * without a second query after activation.
     */
    @Query("""
        SELECT u FROM User u
        LEFT JOIN FETCH u.roles
        WHERE u.email = :email
          AND u.entraObjectId = 'PENDING'
          AND u.isActive = false
        """)
    Optional<User> findPendingByEmailWithRoles(@Param("email") String email);

    /**
     * Sprint G+: Used by IdentityService.syncUserFromToken() as email fallback.
     *
     * When no user is found by entraObjectId (sub), we check if a user with
     * the same email exists but was invited before their first login.
     * Covers two cases:
     *  - entra_object_id IS NULL (not yet activated by enrichment path)
     *  - entra_object_id already set (activated by enrichment, now /api/auth/me fires)
     * Using findByEmail handles both — we just need the user record.
     */
    Optional<User> findByEmail(String email);

    boolean existsByEntraObjectId(String entraObjectId);

    /**
     * Sprint G: Used by EnrichmentService.
     * Only returns active users — inactive users get NO_ACCESS.
     */
    Optional<User> findByEntraObjectIdAndIsActiveTrue(String entraObjectId);

    @Query("""
        SELECT u FROM User u
        JOIN FETCH u.roles
        WHERE u.entraObjectId = :subject
        """)
    Optional<User> findByEntraObjectIdWithRoles(
            @Param("subject") String subject);

    @Modifying
    @Query("""
        UPDATE User u
        SET u.lastLogin = :lastLogin
        WHERE u.entraObjectId = :subject
        """)
    void updateLastLogin(
            @Param("subject") String subject,
            @Param("lastLogin") OffsetDateTime lastLogin);

    List<User> findByDepartmentId(Integer departmentId);

    List<User> findByIsActiveTrue();
}