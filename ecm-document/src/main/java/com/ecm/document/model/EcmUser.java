package com.ecm.document.model;

import jakarta.persistence.*;
import lombok.Getter;

/**
 * Read-only projection of ecm_core.users — used only to resolve the
 * Okta subject (entraObjectId / JWT sub claim) to the integer user PK
 * that documents.uploaded_by requires.
 *
 * This entity intentionally maps ONLY the two columns it needs.
 * Hibernate validates (ddl-auto: validate) against the real table safely
 * because extra columns in the DB are ignored during validation.
 */
@Entity
@Table(name = "users", schema = "ecm_core")
@Getter
public class EcmUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "entra_object_id", nullable = false, unique = true)
    private String entraObjectId;
}