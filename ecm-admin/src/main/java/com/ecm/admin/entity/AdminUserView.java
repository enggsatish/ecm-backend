package com.ecm.admin.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;

import java.time.OffsetDateTime;

/**
 * Read-only JPA projection over ecm_core.users (owned by ecm-identity).
 * No writes ever come from ecm-admin to this table.
 *
 * Column names must match the native SQL column aliases returned by
 * AdminUserViewRepository.search() — snake_case matches PostgreSQL column
 * names directly, and Hibernate maps them to camelCase fields via
 * the @Column(name=...) annotations below.
 */
@Entity
@Immutable
@Table(name = "users", schema = "ecm_core")
public class AdminUserView {

    @Id
    private Integer id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "department_id")
    private Integer departmentId;

    @Column(name = "is_active")
    private Boolean isActive;

    @Column(name = "last_login")
    private OffsetDateTime lastLogin;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "entra_object_id")
    private String entraObjectId;

    // Read-only — no setters
    public Integer getId()               { return id; }
    public String getEmail()             { return email; }
    public String getDisplayName()       { return displayName; }
    public Integer getDepartmentId()     { return departmentId; }
    public Boolean getIsActive()         { return isActive; }
    public OffsetDateTime getLastLogin() { return lastLogin; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public String getEntraObjectId()     { return entraObjectId; }
}