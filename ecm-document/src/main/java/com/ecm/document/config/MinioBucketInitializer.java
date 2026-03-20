package com.ecm.document.config;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Ensures all required MinIO buckets exist at application startup.
 *
 * WHY THIS EXISTS:
 *   On a fresh Docker environment (docker-compose up from scratch), MinIO
 *   starts with zero buckets. The first document upload immediately fails:
 *     io.minio.errors.ErrorResponseException: The specified bucket does not exist
 *
 *   Previously, buckets were created manually in the MinIO web console (port 9001).
 *   This is error-prone and breaks every fresh dev environment wipe.
 *
 *   This initialiser runs once at startup — it is idempotent and safe to call
 *   on environments where the buckets already exist (bucketExists check first).
 *
 * BUCKETS:
 *   ecm-documents  — curated, validated document store (main bucket)
 *   ecm-temp       — temporary uploads before validation (future use)
 *   ecm-templates  — form/document templates (ecm-eforms)
 *   ecm-archive    — retention-triggered archive bucket
 *
 * NOTE: ecm-intake (Sprint L) is created by ecm-ingestion, not here.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MinioBucketInitializer {

    private final MinioClient minioClient;

    @Value("${ecm.storage.bucket:ecm-documents}")
    private String primaryBucket;

    /** All buckets this service needs. Add ecm-intake here once Sprint L is wired. */
    private static final List<String> REQUIRED_BUCKETS = List.of(
            "ecm-documents",
            "ecm-temp",
            "ecm-templates",
            "ecm-archive"
    );

    @PostConstruct
    public void ensureBucketsExist() {
        log.info("MinIO bucket check starting — endpoint will be verified implicitly");
        int created = 0;
        int existing = 0;
        int failed = 0;

        for (String bucket : REQUIRED_BUCKETS) {
            try {
                boolean exists = minioClient.bucketExists(
                        BucketExistsArgs.builder().bucket(bucket).build()
                );

                if (exists) {
                    log.debug("MinIO bucket already exists: {}", bucket);
                    existing++;
                } else {
                    minioClient.makeBucket(
                            MakeBucketArgs.builder().bucket(bucket).build()
                    );
                    log.info("MinIO bucket created: {}", bucket);
                    created++;
                }

            } catch (Exception ex) {
                // Log at ERROR but do NOT crash the application — services like
                // ecm-eforms can still serve form definitions even if MinIO is down.
                // The actual upload will fail with a clear error at that point.
                log.error("Failed to ensure MinIO bucket '{}': {} — {}",
                        bucket, ex.getClass().getSimpleName(), ex.getMessage());
                failed++;
            }
        }

        if (failed == 0) {
            log.info("MinIO buckets ready: {} existing, {} created", existing, created);
        } else {
            log.warn("MinIO bucket init: {} existing, {} created, {} FAILED — " +
                            "check MinIO connectivity at {}", existing, created, failed,
                    "http://localhost:9000 (default)");
        }
    }
}