package com.ecm.ocr.service;

import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.errors.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.*;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

@Slf4j
@Service
@RequiredArgsConstructor
public class MinioFetchService {

    private final MinioClient minioClient;

    /**
     * Fetches a document from MinIO and returns its content as a byte array.
     * The full file is loaded into memory before OCR processing.
     *
     * Callers should enforce the max-file-size-bytes limit BEFORE calling this.
     */
    public byte[] fetchBytes(String bucket, String objectKey) {
        log.debug("Fetching from MinIO: bucket={}, key={}", bucket, objectKey);
        try (InputStream is = minioClient.getObject(
                GetObjectArgs.builder().bucket(bucket).object(objectKey).build())) {
            byte[] bytes = is.readAllBytes();
            log.debug("Fetched {} bytes from MinIO: {}/{}", bytes.length, bucket, objectKey);
            return bytes;
        } catch (MinioException | InvalidKeyException | NoSuchAlgorithmException | IOException e) {
            throw new MinioFetchException("Failed to fetch document from MinIO: " + objectKey, e);
        }
    }

    public static class MinioFetchException extends RuntimeException {
        public MinioFetchException(String msg, Throwable cause) { super(msg, cause); }
    }
}
