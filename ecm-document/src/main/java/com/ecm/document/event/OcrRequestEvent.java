package com.ecm.document.event;

import java.util.UUID;

/**
 * Published to RabbitMQ so the future ecm-ocr service can pick up the work.
 *
 * @param documentId  UUID of the Document record.
 * @param storageBucket MinIO bucket containing the file.
 * @param storageKey    MinIO object key for the file.
 * @param contentType   MIME type; OCR only applies to PDFs and images.
 * @param uploadedBy    Originating user, for traceability.
 */
public record OcrRequestEvent(
        UUID documentId,
        String storageBucket,
        String storageKey,
        String contentType,
        String uploadedBy,
        Integer categoryId,   // ← ADD THIS
        String documentName   // ← ADD THIS
) {}