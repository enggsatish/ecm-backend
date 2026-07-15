package com.ecm.batch.entity;

public enum BatchJobStatus {
    CREATED,
    QUEUED,
    PROCESSING,
    COMPLETED,
    COMPLETED_WITH_ERRORS,
    FAILED
}
