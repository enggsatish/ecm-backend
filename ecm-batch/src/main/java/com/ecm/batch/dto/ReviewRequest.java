package com.ecm.batch.dto;

import java.util.UUID;

public record ReviewRequest(
        Integer finalCategoryId,
        UUID finalCustomerId,
        String partyExternalId,
        String reviewNotes
) {}
