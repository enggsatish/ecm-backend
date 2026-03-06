package com.ecm.document.storage;

import java.io.InputStream;

/**
 * Lightweight wrapper returned when downloading a stored file.
 */
public record StorageObject(
        InputStream content,
        String contentType,
        long contentLength
) {}