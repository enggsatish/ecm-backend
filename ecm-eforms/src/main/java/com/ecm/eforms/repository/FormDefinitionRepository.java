package com.ecm.eforms.repository;

import com.ecm.eforms.model.entity.FormDefinition;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FormDefinitionRepository extends JpaRepository<FormDefinition, UUID> {

    /** Get the current PUBLISHED version for a formKey */
    Optional<FormDefinition> findByTenantIdAndFormKeyAndStatus(
        String tenantId, String formKey, String status);

    /** Get a specific version */
    Optional<FormDefinition> findByTenantIdAndFormKeyAndVersion(
        String tenantId, String formKey, Integer version);

    /** Version history — newest first */
    List<FormDefinition> findByTenantIdAndFormKeyOrderByVersionDesc(
        String tenantId, String formKey);

    /** Highest version number for a formKey (used when creating next version) */
    @Query("SELECT COALESCE(MAX(d.version), 0) FROM FormDefinition d " +
           "WHERE d.tenantId = :tenantId AND d.formKey = :formKey")
    Integer findMaxVersionByTenantIdAndFormKey(
        @Param("tenantId") String tenantId, @Param("formKey") String formKey);

    /** Paginated list with optional filters */
    @Query("SELECT d FROM FormDefinition d WHERE d.tenantId = :tenantId " +
           "AND (:status IS NULL OR d.status = :status) " +
           "AND (:productType IS NULL OR d.productTypeCode = :productType) " +
           "AND (:formType IS NULL OR d.formTypeCode = :formType) " +
           "ORDER BY d.updatedAt DESC")
    Page<FormDefinition> findAllWithFilters(
        @Param("tenantId")    String tenantId,
        @Param("status")      String status,
        @Param("productType") String productType,
        @Param("formType")    String formType,
        Pageable pageable);

    /** Archive the current PUBLISHED version — called atomically during publish */
    @Modifying
    @Query("UPDATE FormDefinition d SET d.status = 'ARCHIVED', d.updatedBy = :userId " +
           "WHERE d.tenantId = :tenantId AND d.formKey = :formKey AND d.status = 'PUBLISHED'")
    int archivePublishedVersion(
        @Param("tenantId") String tenantId,
        @Param("formKey")  String formKey,
        @Param("userId")   String userId);
}
