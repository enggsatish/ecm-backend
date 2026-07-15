package com.ecm.batch.dto;

public record CreateBatchRequest(
        String source,
        String notes
) {}
