package com.ecm.document.storage;

import com.ecm.document.dto.DocumentUploadRequest;
import com.ecm.document.exception.StorageException;
import io.minio.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * MinIO-backed storage implementation.
 *
 * v5.0: UUID-based flat storage path convention.
 *
 * Path format:
 *   {tenantId}/{documentId}/v{version}
 *
 * tenantId defaults to "default" until multi-tenancy is activated.
 * The object key (without bucket prefix) is stored in documents.blob_storage_key;
 * the bucket name is stored separately in documents.blob_storage_bucket.
 *
 * Existing documents remain at their original paths — the new convention
 * applies only to new uploads after the V5 migration.
 *
 * ── SPRINT I FIX: MinioConfig now supplies a custom OkHttpClient ──────────────
 * The default OkHttp connection pool reuses stale sockets after MinIO restarts,
 * causing "Broken pipe / Connection reset" on uploads. The fix is in MinioConfig —
 * this class is unchanged in its logic. See MinioConfig.java for details.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MinioDocumentStorageService implements DocumentStorageService {

    private final MinioClient minioClient;

    // MinIO SDK minimum part size for multipart upload is 5 MiB.
    // -1 tells the SDK to auto-calculate: single PUT if file < 5MB,
    // multipart if >= 5MB. This is the correct value — do NOT use 0.
    private static final long AUTO_PART_SIZE = -1L;

    // ─── store() — UUID-based flat path ─────────────────────────────────────────

    /**
     * Stores a file in MinIO using the UUID-based flat path convention (v5.0).
     *
     * @param bucket     destination bucket
     * @param documentId UUID for the document (used in path)
     * @param file       the uploaded file
     * @param metadata   upload metadata (may be null for legacy calls)
     * @return object key (without bucket prefix) — bucket stored separately
     */
    public String store(String bucket, UUID documentId, MultipartFile file,
                        DocumentUploadRequest metadata) {
        String key = buildStoragePath(metadata, documentId, file.getOriginalFilename());

        log.debug("MinIO upload starting: bucket={}, key={}, sizeBytes={}, contentType={}",
                bucket, key, file.getSize(), file.getContentType());

        try {
            // Build user metadata tags — safety net for orphan detection
            Map<String, String> userMeta = buildUserMetadata(metadata, documentId, file);

            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucket)
                            .object(key)
                            .stream(file.getInputStream(), file.getSize(), AUTO_PART_SIZE)
                            .contentType(resolveContentType(file))
                            .userMetadata(userMeta)
                            .build()
            );
            log.info("Stored: bucket={}, key={}, sizeBytes={}", bucket, key, file.getSize());
            // v5.0: return just the key, bucket stored separately
            return key;

        } catch (Exception ex) {
            // Log the root cause explicitly — OkHttp wraps socket errors in generic Exception
            Throwable root = ex.getCause() != null ? ex.getCause() : ex;
            log.error("MinIO store FAILED: bucket={}, key={}, sizeBytes={} — {}: {}",
                    bucket, key, file.getSize(),
                    root.getClass().getSimpleName(), root.getMessage());
            throw new StorageException("Failed to store file in MinIO: " + bucket + "/" + key, ex);
        }
    }

    /**
     * Store raw bytes at a specific bucket/key path (overwrites if exists).
     * Used for replacing document content (e.g., signed PDF replacing unsigned).
     */
    public void storeRaw(String bucket, String key, java.io.InputStream inputStream,
                         long size, String contentType) {
        try {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucket)
                            .object(key)
                            .stream(inputStream, size, AUTO_PART_SIZE)
                            .contentType(contentType != null ? contentType : "application/octet-stream")
                            .build()
            );
            log.info("Stored (raw): bucket={}, key={}, sizeBytes={}", bucket, key, size);
        } catch (Exception ex) {
            throw new StorageException("Failed to store raw content in MinIO: " + bucket + "/" + key, ex);
        }
    }

    // ─── retrieve / delete — unchanged ────────────────────────────────────────

    @Override
    public StorageObject retrieve(String bucket, String key) {
        try {
            GetObjectResponse response = minioClient.getObject(
                    GetObjectArgs.builder().bucket(bucket).object(key).build()
            );
            StatObjectResponse stat = minioClient.statObject(
                    StatObjectArgs.builder().bucket(bucket).object(key).build()
            );
            return new StorageObject((InputStream) response, stat.contentType(), stat.size());
        } catch (Exception ex) {
            throw new StorageException("Failed to retrieve from MinIO: " + bucket + "/" + key, ex);
        }
    }

    @Override
    public void delete(String bucket, String key) {
        try {
            minioClient.removeObject(
                    RemoveObjectArgs.builder().bucket(bucket).object(key).build()
            );
            log.info("Deleted: bucket={}, key={}", bucket, key);
        } catch (Exception ex) {
            throw new StorageException("Failed to delete from MinIO: " + bucket + "/" + key, ex);
        }
    }

    // ─── Copy between buckets (for archive/restore) ──────────────────────────

    /**
     * Copies an object from one bucket to another (same key).
     * Used for archive (ecm-documents → ecm-archive) and restore (reverse).
     */
    public void copy(String sourceBucket, String sourceKey,
                     String destBucket) {
        try {
            minioClient.copyObject(
                    CopyObjectArgs.builder()
                            .bucket(destBucket)
                            .object(sourceKey)
                            .source(CopySource.builder()
                                    .bucket(sourceBucket)
                                    .object(sourceKey)
                                    .build())
                            .build()
            );
            log.info("Copied: {}/{} → {}/{}", sourceBucket, sourceKey, destBucket, sourceKey);
        } catch (Exception ex) {
            throw new StorageException("Failed to copy object: " +
                    sourceBucket + "/" + sourceKey + " → " + destBucket, ex);
        }
    }

    /**
     * Lists all object keys in a bucket (for orphan detection).
     * Returns a map of objectKey → size in bytes.
     */
    public Map<String, Long> listObjects(String bucket) {
        Map<String, Long> objects = new HashMap<>();
        try {
            var results = minioClient.listObjects(
                    ListObjectsArgs.builder().bucket(bucket).recursive(true).build());
            for (var result : results) {
                var item = result.get();
                objects.put(item.objectName(), item.size());
            }
        } catch (Exception ex) {
            log.error("Failed to list objects in bucket {}: {}", bucket, ex.getMessage());
        }
        return objects;
    }

    // ─── User metadata ──────────────────────────────────────────────────────

    /**
     * Builds user metadata tags stored on the MinIO object itself.
     * These survive even if the DB metadata row is lost — safety net for
     * orphan detection and compliance audit.
     */
    private Map<String, String> buildUserMetadata(DocumentUploadRequest req,
                                                   UUID documentId,
                                                   MultipartFile file) {
        Map<String, String> meta = new HashMap<>();
        meta.put("ecm-document-id", documentId.toString());
        meta.put("ecm-uploaded-at", OffsetDateTime.now().toString());
        meta.put("ecm-original-filename", file.getOriginalFilename() != null
                ? file.getOriginalFilename() : "unknown");
        meta.put("ecm-content-type", file.getContentType() != null
                ? file.getContentType() : "application/octet-stream");
        meta.put("ecm-file-size", String.valueOf(file.getSize()));

        if (req != null) {
            if (req.partyExternalId() != null)
                meta.put("ecm-party-id", req.partyExternalId());
            if (req.categoryId() != null)
                meta.put("ecm-category-id", String.valueOf(req.categoryId()));
            if (req.segmentId() != null)
                meta.put("ecm-segment-id", String.valueOf(req.segmentId()));
            if (req.productLineId() != null)
                meta.put("ecm-product-line-id", String.valueOf(req.productLineId()));
        }
        return meta;
    }

    // ─── Path building ────────────────────────────────────────────────────────

    /**
     * Builds the MinIO object key using UUID-based flat convention (v5.0).
     *
     * Path convention:
     *   {tenantId}/{documentId}/v{version}
     *
     * tenantId defaults to "default" until multi-tenancy is activated.
     */
    private String buildStoragePath(DocumentUploadRequest req, UUID documentId,
                                    String originalFilename) {
        String tenantId = "default";
        int version = 1;  // version tracking handled at DB level
        return String.format("%s/%s/v%d", tenantId, documentId, version);
    }

    /** Replaces characters unsafe for object-store keys. */
    private String sanitise(String name) {
        if (name == null) return "document";
        return name.replaceAll("[^a-zA-Z0-9._\\-]", "_").toLowerCase();
    }

    /** Ensures content type is never null — MinIO rejects null content-type. */
    private String resolveContentType(MultipartFile file) {
        String ct = file.getContentType();
        return (ct != null && !ct.isBlank()) ? ct : "application/octet-stream";
    }
}