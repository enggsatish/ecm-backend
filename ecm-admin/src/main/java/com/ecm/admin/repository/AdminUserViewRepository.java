package com.ecm.admin.repository;

import com.ecm.admin.entity.AdminUserView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface AdminUserViewRepository extends JpaRepository<AdminUserView, Integer> {

    Page<AdminUserView> findByIsActive(Boolean isActive, Pageable pageable);

    /**
     * Native SQL query — bypasses Hibernate 6 JPQL type inference entirely.
     *
     * WHY NATIVE INSTEAD OF JPQL:
     * Hibernate 6 resolves the JDBC type of String fields on @Immutable entities
     * mapped to a foreign schema (ecm_core ≠ application default_schema ecm_admin)
     * as VARBINARY/bytea instead of varchar. This causes PostgreSQL to reject:
     *   ERROR: function lower(bytea) does not exist
     *
     * Annotations like @JdbcTypeCode and columnDefinition only affect DDL or
     * EntityManager-level type binding — they do NOT override the type Hibernate
     * uses when compiling JPQL function calls like LOWER() into SQL.
     *
     * A native query sends raw SQL directly to PostgreSQL. PostgreSQL reads its
     * own catalog for column types and correctly resolves lower(varchar). The
     * Hibernate type inference layer is completely bypassed.
     *
     * WHY ILIKE:
     * PostgreSQL's ILIKE is a case-insensitive LIKE. Using ILIKE instead of
     * LOWER(...) LIKE LOWER(...) is cleaner, faster (can use a trigram index),
     * and avoids the bytea type inference issue on both sides of the comparison.
     *
     * The countQuery is required by Spring Data for Page results — without it,
     * Spring Data attempts to derive the count query from the main query and
     * fails because it cannot parse native SQL ORDER BY / fetch clauses.
     */
    @Query(
            value = """
            SELECT *
            FROM ecm_core.users u
            WHERE (:isActive IS NULL OR u.is_active = :isActive)
              AND (:departmentId IS NULL OR u.department_id = :departmentId)
              AND (:search IS NULL
                   OR u.email       ILIKE CONCAT('%', :search, '%')
                   OR u.display_name ILIKE CONCAT('%', :search, '%'))
            ORDER BY u.email
            """,
            countQuery = """
            SELECT COUNT(*)
            FROM ecm_core.users u
            WHERE (:isActive IS NULL OR u.is_active = :isActive)
              AND (:departmentId IS NULL OR u.department_id = :departmentId)
              AND (:search IS NULL
                   OR u.email       ILIKE CONCAT('%', :search, '%')
                   OR u.display_name ILIKE CONCAT('%', :search, '%'))
            """,
            nativeQuery = true
    )
    Page<AdminUserView> search(@Param("isActive") Boolean isActive,
                               @Param("departmentId") Integer departmentId,
                               @Param("search") String search,
                               Pageable pageable);
}