package com.ecm.document.storage;

import com.ecm.document.dto.DocumentUploadRequest;
import com.ecm.document.exception.StorageException;
import io.minio.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.UUID;

/**
 * MinIO-backed storage implementation.
 *
 * Sprint-C: Hierarchy-aware storage path convention.
 *
 * Path formats:
 *   With full hierarchy:  {tenantId}/{segCode}/{plCode}/{catCode}/{uuid}/{filename}
 *   Without product line: {tenantId}/{segCode}/general/{catCode}/{uuid}/{filename}
 *   Without segment:      {tenantId}/general/general/{catCode}/{uuid}/{filename}
 *   Legacy (no context):  {tenantId}/legacy/{uuid}/{filename}
 *
 * tenantId defaults to "default" until multi-tenancy is wired.
 * The full path (bucket + "/" + key) is stored in documents.blob_storage_path.
 *
 * Existing documents remain at their original paths — the new convention
 * applies only to new uploads after the V5 migration.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MinioDocumentStorageService implements DocumentStorageService {

    private final MinioClient minioClient;

    // ─── store() — hierarchy-aware ────────────────────────────────────────────

    /**
     * Stores a file in MinIO using the hierarchy path convention.
     *
     * @param bucket     destination bucket
     * @param documentId UUID for the document (used in path)
     * @param file       the uploaded file
     * @param metadata   upload metadata (may be null for legacy calls)
     * @return full blob path: "{bucket}/{key}" — persisted to documents.blob_storage_path
     */
    public String store(String bucket, UUID documentId, MultipartFile file,
                        DocumentUploadRequest metadata) {
        String key      = buildStoragePath(metadata, documentId, file.getOriginalFilename());
        String blobPath = bucket + "/" + key;
        try {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucket)
                            .object(key)
                            .stream(file.getInputStream(), file.getSize(), -1)
                            .contentType(file.getContentType())
                            .build()
            );
            log.info("Stored: bucket={}, key={}", bucket, key);
            return blobPath;
        } catch (Exception ex) {
            throw new StorageException("Failed to store file in MinIO: " + blobPath, ex);
        }
    }

    /**
     * Backwards-compatible overload — no hierarchy context.
     * Used by legacy callers and unit tests that don't yet pass metadata.
     */
//    @Override
//    public String store(String bucket, UUID documentId, MultipartFile file) {
//        return store(bucket, documentId, file, null);
//    }

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

    // ─── Path building ────────────────────────────────────────────────────────

    /**
     * Builds the MinIO object key including hierarchy context.
     *
     * Path convention (Sprint-C):
     *   {tenantId}/{segmentCode}/{productLineCode}/{categoryCode}/{uuid}/{filename}
     *
     * Fallback values when metadata is absent:
     *   segmentCode     → "general"
     *   productLineCode → "general"
     *   categoryCode    → "uncategorised"
     *
     * tenantId is hardcoded to "default" until multi-tenancy is activated.
     */
    private String buildStoragePath(DocumentUploadRequest req, UUID documentId,
                                    String originalFilename) {
        String tenantId = "default";
        String segCode  = "general";
        String plCode   = "general";
        String catCode  = "uncategorised";

        if (req != null) {
            if (req.segmentCode()     != null && !req.segmentCode().isBlank())
                segCode = sanitise(req.segmentCode().toLowerCase());
            if (req.productLineCode() != null && !req.productLineCode().isBlank())
                plCode  = sanitise(req.productLineCode().toLowerCase());
        }

        String filename = sanitise(originalFilename != null ? originalFilename : "document");
        return String.format("%s/%s/%s/%s/%s/%s",
                tenantId, segCode, plCode, catCode, documentId, filename);
    }

    /** Replaces characters unsafe for object-store keys. */
    private String sanitise(String name) {
        if (name == null) return "document";
        return name.replaceAll("[^a-zA-Z0-9._\\-]", "_").toLowerCase();
    }
}