package com.ecm.document.entity;

public enum DocumentStatus {
    /** Uploaded and stored; OCR event published but not yet processed. */
    PENDING_OCR,
    /** OCR done, no category detected — needs human classification. */
    PENDING_CLASSIFICATION,
    /** OCR done, category detected but needs human to link customer or verify. */
    NEEDS_ASSIGNMENT,
    /** Fully ready — classification complete, customer linked (or manual upload). */
    ACTIVE,
    /** Document has been soft-deleted. */
    DELETED,
    /** OCR processing failed — can be retried. */
    OCR_FAILED,
    /** Sent to DocuSign for signature — waiting for signer. */
    PENDING_SIGNATURE,
    /** Signed PDF received back from DocuSign. */
    SIGNED,
    /** Signer declined to sign via DocuSign. */
    SIGN_DECLINED,
    /** Moved to archive bucket — still downloadable but read-only. */
    ARCHIVED,
    /** Binary permanently removed from storage. Metadata row kept for audit. */
    PURGED
}
