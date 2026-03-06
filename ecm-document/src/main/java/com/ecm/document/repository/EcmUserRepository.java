package com.ecm.document.repository;

import com.ecm.document.model.EcmUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Read-only access to ecm_core.users — only used by ecm-document
 * to resolve the JWT subject (entraObjectId) → integer user PK
 * for the documents.uploaded_by FK column.
 */
public interface EcmUserRepository extends JpaRepository<EcmUser, Integer> {

    Optional<EcmUser> findByEntraObjectId(String entraObjectId);
}