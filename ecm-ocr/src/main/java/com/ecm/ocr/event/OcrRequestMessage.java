package com.ecm.ocr.event;

import java.util.UUID;

/**
 * Deserialized from ecm.ocr.requests queue.
 * Must match the payload shape published by ecm-document's OcrRequestEvent exactly.
 *
 * IMPORTANT: categoryId is Integer (not UUID) because ecm-document.Document.categoryId
 * is an Integer FK to ecm_core.document_categories.id (integer PK).
 * A UUID here causes Jackson deserialization failure at runtime.
 */
public record OcrRequestMessage(
        UUID    documentId,
        String  storageBucket,
        String  storageKey,
        String  contentType,
        String  uploadedBy,
        Integer categoryId,   // ← Integer, matches OcrRequestEvent and documents.category_id
        String  documentName
) {}
