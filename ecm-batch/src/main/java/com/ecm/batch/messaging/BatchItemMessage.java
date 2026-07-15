package com.ecm.batch.messaging;

import java.util.UUID;

public record BatchItemMessage(
        UUID batchId,
        UUID itemId,
        UUID documentId,
        String storageBucket,
        String storageKey,
        String originalFilename
) {}
