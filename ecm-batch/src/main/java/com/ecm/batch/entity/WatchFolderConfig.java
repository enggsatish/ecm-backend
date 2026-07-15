package com.ecm.batch.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * Maps to ecm_admin.watch_folder_config — cross-schema read.
 * Batch service reads/writes this directly (same database, different schema).
 */
@Entity
@Table(name = "watch_folder_config", schema = "ecm_admin")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WatchFolderConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "tenant_id", nullable = false, length = 100)
    @Builder.Default
    private String tenantId = "default";

    @Column(name = "folder_path", nullable = false, length = 500)
    private String folderPath;

    @Column(name = "poll_interval", nullable = false)
    @Builder.Default
    private Integer pollInterval = 300;

    @Column(name = "processed_path", length = 500)
    private String processedPath;

    @Column(name = "failed_path", length = 500)
    private String failedPath;

    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private Boolean enabled = false;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
