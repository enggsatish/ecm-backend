package com.ecm.document.entity;

public enum DocumentStatus {
    /** Uploaded and stored; OCR event published but not yet processed. */
    PENDING_OCR,
    /** OCR completed; document is fully indexed. */
    ACTIVE,
    /** Document has been soft-deleted. */
    DELETED,
    OCR_FAILED,   // ← ADD: OCR processing failed; document still accessible
    ARCHIVED
}