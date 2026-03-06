package com.ecm.admin.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;

/** Read-only view of ecm_core.roles */
@Entity
@Immutable
@Table(name = "roles", schema = "ecm_core")
public class RoleView {

    @Id
    private Integer id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column
    private String description;

    public Integer getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
}
