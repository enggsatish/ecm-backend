package com.ecm.ocr.classification;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * @param customerId   party UUID (parties.id) — for case linking
 * @param externalId   party external_id (e.g., CUST-RET-003) — stored on document as party_external_id
 * @param confidence   match confidence 0-100
 * @param matchedField description of which field matched
 * @param candidateIds all candidate party UUIDs
 */
public record CustomerMatchResult(
        UUID customerId,
        String externalId,
        BigDecimal confidence,
        String matchedField,
        List<UUID> candidateIds
) {}
