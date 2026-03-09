package com.ecm.admin.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Typed credential store for third-party integrations.
 *
 * config  JSONB — non-sensitive fields (base_url, account_id, etc.)
 * secrets JSONB — AES-GCM encrypted blobs; service layer encrypts before write
 *                 and decrypts on read.  NEVER returned verbatim to clients.
 */
@Entity
@Table(schema = "ecm_admin", name = "integration_configs")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IntegrationConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "tenant_id", nullable = false, length = 50)
    @Builder.Default
    private String tenantId = "default";

    /** DOCUSIGN | AZURE_AI | CORE_BANKING */
    @Column(name = "system_key", nullable = false, length = 50)
    private String systemKey;

    @Column(name = "display_name", nullable = false, length = 100)
    @Builder.Default
    private String displayName = "";

    @Column(nullable = false)
    @Builder.Default
    private Boolean enabled = false;

    /** Non-sensitive configuration fields */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    @Builder.Default
    private Map<String, Object> config = new HashMap<>();

    /**
     * AES-GCM encrypted sensitive values.
     * Each entry is: { "fieldName": "base64(iv):base64(ciphertext)" }
     * Never serialised directly to API responses.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    @Builder.Default
    private Map<String, Object> secrets = new HashMap<>();

    /** OK | FAILED | null = never tested */
    @Column(name = "test_status", length = 20)
    private String testStatus;

    @Column(name = "tested_at")
    private OffsetDateTime testedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private OffsetDateTime updatedAt = OffsetDateTime.now();
}