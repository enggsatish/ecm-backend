package com.ecm.identity.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "roles", schema = "ecm_core")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Role {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false, unique = true)
    private String name;           // ECM_ADMIN, ECM_BACKOFFICE etc.

    private String description;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;
}