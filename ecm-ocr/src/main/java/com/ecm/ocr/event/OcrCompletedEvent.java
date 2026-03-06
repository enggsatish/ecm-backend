package com.ecm.ocr.event;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Published to ecm.ocr.completed (fanout) after successful OCR.
 * Downstream subscribers (ecm-notification, future ecm-search) consume this.
 */
public record OcrCompletedEvent(
        UUID            documentId,
        String          documentName,
        String          extractedText,
        Map<String, Object> extractedFields,
        boolean         tessUsed,
        int             pageCount,
        OffsetDateTime  completedAt
) {}
