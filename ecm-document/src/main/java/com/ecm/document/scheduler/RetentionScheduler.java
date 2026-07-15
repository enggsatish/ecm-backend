package com.ecm.document.scheduler;

import com.ecm.document.entity.Document;
import com.ecm.document.entity.DocumentStatus;
import com.ecm.document.repository.DocumentRepository;
import com.ecm.document.storage.MinioDocumentStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Retention enforcement scheduler.
 *
 * Runs daily at 2:00 AM:
 *   1. Auto-archive: ACTIVE documents past their retention policy's archive_after_days
 *   2. Auto-purge:   ARCHIVED documents past their policy's purge_after_days
 *   3. Orphan detect: MinIO objects with no matching DB row → log + notify
 *
 * Uses JdbcTemplate for cross-schema reads (retention policies in ecm_admin).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetentionScheduler {

    private final DocumentRepository documentRepo;
    private final MinioDocumentStorageService storageService;
    private final JdbcTemplate jdbc;

    @Value("${ecm.storage.bucket:ecm-documents}")
    private String activeBucket;

    @Value("${ecm.storage.archive-bucket:ecm-archive}")
    private String archiveBucket;

    @Value("${ecm.retention.enabled:true}")
    private boolean retentionEnabled;

    // ── 1. Auto-Archive ──────────────────────────────────────────────────────

    @Scheduled(cron = "${ecm.retention.archive-cron:0 0 2 * * *}") // 2:00 AM daily
    @Transactional
    public void autoArchive() {
        if (!retentionEnabled) {
            log.debug("Retention scheduler disabled");
            return;
        }

        log.info("Retention auto-archive job started");
        int archived = 0;

        try {
            // Fetch all active retention policies
            List<Map<String, Object>> policies = jdbc.queryForList("""
                SELECT id, category_id, product_code, archive_after_days
                FROM ecm_admin.retention_policies
                WHERE is_active = true AND archive_after_days > 0
                ORDER BY priority ASC
            """);

            for (Map<String, Object> policy : policies) {
                Integer categoryId = (Integer) policy.get("category_id");
                int archiveDays = (int) policy.get("archive_after_days");
                Instant cutoff = Instant.now().minus(archiveDays, ChronoUnit.DAYS);

                // Find ACTIVE documents in this category older than the cutoff
                List<Document> eligible;
                if (categoryId != null) {
                    eligible = documentRepo.findByStatusAndCategoryIdAndCreatedAtBefore(
                            DocumentStatus.ACTIVE, categoryId, cutoff);
                } else {
                    // Policy without category — applies to all uncategorized docs
                    eligible = documentRepo.findByStatusAndCategoryIdIsNullAndCreatedAtBefore(
                            DocumentStatus.ACTIVE, cutoff);
                }

                for (Document doc : eligible) {
                    try {
                        archiveDocument(doc);
                        archived++;
                    } catch (Exception e) {
                        log.error("Failed to auto-archive document id={}: {}", doc.getId(), e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.error("Auto-archive job failed: {}", e.getMessage(), e);
        }

        log.info("Retention auto-archive job completed: {} documents archived", archived);
    }

    private void archiveDocument(Document doc) {
        String bucket = doc.getStorageBucket();
        String key = doc.getStorageKey();

        storageService.copy(bucket, key, archiveBucket);
        storageService.delete(bucket, key);

        doc.setStorageBucket(archiveBucket);
        doc.setStatus(DocumentStatus.ARCHIVED);
        doc.setArchivedAt(Instant.now());
        doc.setArchivedBy("SYSTEM:retention-scheduler");
        documentRepo.save(doc);

        log.debug("Auto-archived: id={}, age={}d", doc.getId(),
                ChronoUnit.DAYS.between(doc.getCreatedAt(), Instant.now()));
    }

    // ── 2. Auto-Purge ────────────────────────────────────────────────────────

    @Scheduled(cron = "${ecm.retention.purge-cron:0 30 2 * * *}") // 2:30 AM daily
    @Transactional
    public void autoPurge() {
        if (!retentionEnabled) return;

        log.info("Retention auto-purge job started");
        int purged = 0;

        try {
            List<Map<String, Object>> policies = jdbc.queryForList("""
                SELECT id, category_id, product_code, purge_after_days
                FROM ecm_admin.retention_policies
                WHERE is_active = true AND purge_after_days > 0
                ORDER BY priority ASC
            """);

            for (Map<String, Object> policy : policies) {
                Integer categoryId = (Integer) policy.get("category_id");
                int purgeDays = (int) policy.get("purge_after_days");
                Instant cutoff = Instant.now().minus(purgeDays, ChronoUnit.DAYS);

                List<Document> eligible;
                if (categoryId != null) {
                    eligible = documentRepo.findByStatusAndCategoryIdAndArchivedAtBefore(
                            DocumentStatus.ARCHIVED, categoryId, cutoff);
                } else {
                    eligible = documentRepo.findByStatusAndCategoryIdIsNullAndArchivedAtBefore(
                            DocumentStatus.ARCHIVED, cutoff);
                }

                for (Document doc : eligible) {
                    try {
                        purgeDocument(doc);
                        purged++;
                    } catch (Exception e) {
                        log.error("Failed to auto-purge document id={}: {}", doc.getId(), e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.error("Auto-purge job failed: {}", e.getMessage(), e);
        }

        log.info("Retention auto-purge job completed: {} documents purged", purged);
    }

    private void purgeDocument(Document doc) {
        // Delete the binary from archive bucket
        try {
            storageService.delete(doc.getStorageBucket(), doc.getStorageKey());
        } catch (Exception e) {
            log.warn("Binary already missing for purge id={}: {}", doc.getId(), e.getMessage());
        }

        // Update metadata — keep the row for audit
        doc.setStatus(DocumentStatus.PURGED);
        doc.setPurgedAt(Instant.now());
        doc.setStorageKey(null); // binary gone
        documentRepo.save(doc);

        log.debug("Auto-purged: id={}", doc.getId());
    }

    // ── 3. Orphan Detection ──────────────────────────────────────────────────

    @Scheduled(cron = "${ecm.retention.orphan-cron:0 0 3 * * *}") // 3:00 AM daily
    public void detectOrphans() {
        if (!retentionEnabled) return;

        log.info("Orphan detection job started");
        try {
            Map<String, Long> minioObjects = storageService.listObjects(activeBucket);
            int orphanCount = 0;

            for (Map.Entry<String, Long> entry : minioObjects.entrySet()) {
                String key = entry.getKey();
                // Extract UUID from path: .../uuid/filename
                UUID documentId = extractUuidFromKey(key);
                if (documentId == null) continue;

                boolean existsInDb = documentRepo.existsById(documentId);
                if (!existsInDb) {
                    orphanCount++;
                    log.warn("ORPHAN DETECTED: bucket={}, key={}, size={} bytes, documentId={}",
                            activeBucket, key, entry.getValue(), documentId);
                }
            }

            if (orphanCount > 0) {
                log.warn("Orphan detection completed: {} orphaned objects found in bucket '{}'",
                        orphanCount, activeBucket);
                // Publish notification event for admin
                try {
                    jdbc.update("""
                        INSERT INTO ecm_core.notifications (recipient, title, body, link, category)
                        SELECT u.email, 'Orphan Documents Detected',
                               ? || ' orphaned documents found in MinIO with no database record.',
                               '/admin/audit', 'SYSTEM'
                        FROM ecm_core.users u
                        JOIN ecm_core.user_roles ur ON ur.user_id = u.id
                        JOIN ecm_core.roles r ON r.id = ur.role_id
                        WHERE r.name IN ('ECM_ADMIN', 'ECM_SUPER_ADMIN') AND u.is_active = true
                    """, String.valueOf(orphanCount));
                } catch (Exception e) {
                    log.warn("Failed to notify admins about orphans: {}", e.getMessage());
                }
            } else {
                log.info("Orphan detection completed: no orphans found");
            }
        } catch (Exception e) {
            log.error("Orphan detection failed: {}", e.getMessage(), e);
        }
    }

    // ── 4. Expired Lock Cleanup ─────────────────────────────────────────────

    @Scheduled(cron = "${ecm.retention.lock-cleanup-cron:0 */15 * * * *}") // every 15 min
    @Transactional
    public void cleanupExpiredLocks() {
        try {
            int cleaned = documentRepo.releaseExpiredLocks(Instant.now());
            if (cleaned > 0) {
                log.info("Released {} expired document locks", cleaned);
            }
        } catch (Exception e) {
            log.warn("Lock cleanup failed: {}", e.getMessage());
        }
    }

    /**
     * Extracts UUID from MinIO key path.
     * Path format: {tenant}/{seg}/{pl}/{cat}/{uuid}/{filename}
     * The UUID is the second-to-last segment.
     */
    private UUID extractUuidFromKey(String key) {
        String[] segments = key.split("/");
        if (segments.length < 2) return null;
        String candidate = segments[segments.length - 2]; // uuid is before filename
        try {
            return UUID.fromString(candidate);
        } catch (IllegalArgumentException e) {
            return null; // not a UUID — skip
        }
    }

    // splitBlobPath removed in v5.0 — bucket and key stored separately on Document entity
}
