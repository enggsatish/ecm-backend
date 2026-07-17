package com.ecm.eforms.repository;

import com.ecm.eforms.model.entity.FormSubmission;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FormSubmissionRepository extends JpaRepository<FormSubmission, UUID> {

    /** Webhook lookup — find submission by DocuSign envelope ID */
    Optional<FormSubmission> findByDocuSignEnvelopeId(String envelopeId);

    /**
     * Fetches a submission with its FormDefinition eagerly joined, so callers that
     * map to a DTO after the transactional service method returns (e.g. controllers)
     * don't hit LazyInitializationException on the closed-session formDefinition proxy.
     */
    @Query("SELECT s FROM FormSubmission s LEFT JOIN FETCH s.formDefinition WHERE s.id = :id")
    Optional<FormSubmission> findByIdWithFormDefinition(@Param("id") UUID id);

    /** User's own submissions — newest first */
    Page<FormSubmission> findByTenantIdAndSubmittedByOrderByCreatedAtDesc(
        String tenantId, String submittedBy, Pageable pageable);

    /** Backoffice list with optional filters */
    @Query("SELECT s FROM FormSubmission s WHERE s.tenantId = :tenantId " +
           "AND (:status IS NULL OR s.status = :status) " +
           "AND (:formKey IS NULL OR s.formKey = :formKey) " +
           "AND (:assignedTo IS NULL OR s.assignedTo = :assignedTo)")
    Page<FormSubmission> findAllWithFilters(
        @Param("tenantId")   String tenantId,
        @Param("status")     String status,
        @Param("formKey")    String formKey,
        @Param("assignedTo") String assignedTo,
        Pageable pageable);

    /** Analytics helper */
    @Query("SELECT COUNT(s) FROM FormSubmission s " +
           "WHERE s.tenantId = :tenantId AND s.formKey = :formKey AND s.status = :status")
    Long countByFormKeyAndStatus(
        @Param("tenantId") String tenantId,
        @Param("formKey")  String formKey,
        @Param("status")   String status);

    /**
     * Fetches the linked FormDefinition's documentCategoryId directly, without
     * navigating the lazy FormSubmission.formDefinition association — needed by
     * webhook processing, which runs outside any active Hibernate session by the
     * time this value is needed.
     */
    @Query("SELECT fd.documentCategoryId FROM FormSubmission s JOIN s.formDefinition fd " +
           "WHERE s.id = :submissionId")
    Optional<Integer> findDocumentCategoryId(@Param("submissionId") UUID submissionId);
}
