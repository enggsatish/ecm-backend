package com.ecm.document.storage;

import com.ecm.document.dto.DocumentUploadRequest;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * Abstraction over the underlying object store.
 * <p>
 * Local / dev → MinIO implementation below.
 * Production (azure profile) → Azure Blob implementation (future module).
 */
public interface DocumentStorageService {

    /**
     * Store a file and return the object key under which it was saved.
     *
     * @param bucket target bucket name
     * @param documentId used to build a deterministic key path
     * @param file multipart upload
     * @return the storage key (relative path within the bucket)
     */
    String store(String bucket, UUID documentId, MultipartFile file,
                 DocumentUploadRequest metadata);

    /**
     * Retrieve a stored object for streaming to the client.
     */
    StorageObject retrieve(String bucket, String key);

    /**
     * Permanently remove an object from the store.
     */
    void delete(String bucket, String key);

    /**
     * Copy an object from one bucket to another (same key).
     * Used for archive (active → archive) and restore (archive → active).
     */
    void copy(String sourceBucket, String sourceKey, String destBucket);

    /**
     * Store raw bytes at a specific bucket/key path (overwrites if exists).
     * Used for replacing document content (e.g., signed PDF replacing unsigned).
     */
    void storeRaw(String bucket, String key, java.io.InputStream inputStream,
                  long size, String contentType);
}