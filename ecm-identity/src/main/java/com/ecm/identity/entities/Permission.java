package com.ecm.identity.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Immutable;

/**
 * Read-only JPA entity for ecm_core.permissions.
 * Seeded by Flyway migration V5. Never written by application code.
 */
@Entity
@Immutable
@Table(name = "permissions", schema = "ecm_core")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Permission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "module_code", nullable = false)
    private String moduleCode;

    @Column(nullable = false)
    private String action;

    /**
     * Unique permission code — e.g. "documents:read", "workflow:approve".
     * This is the value placed in PERMISSION_* Spring authorities.
     */
    @Column(nullable = false, unique = true)
    private String code;

    private String description;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive;
}
