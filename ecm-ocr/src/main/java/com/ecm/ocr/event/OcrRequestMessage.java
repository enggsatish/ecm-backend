package com.ecm.ocr.event;

import java.util.UUID;

/**
 * Deserialized from ecm.ocr.requests queue.
 * Must match the payload shape published by ecm-document.
 */
public record OcrRequestMessage(
        UUID   documentId,
        String storageBucket,
        String storageKey,
        String contentType,
        String uploadedBy,
        UUID   categoryId,
        String documentName
) {}
