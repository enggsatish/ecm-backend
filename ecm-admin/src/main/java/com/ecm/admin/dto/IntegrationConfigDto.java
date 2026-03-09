package com.ecm.admin.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;

/**
 * DTOs for the /api/admin/integrations/* endpoints.
 *
 * Secrets are NEVER returned to the client in plaintext.
 * If a secret field is set in DB, the response shows "*** saved ***".
 */
public class IntegrationConfigDto {

    private static final String MASKED = "*** saved ***";

    /** Response: full DocuSign config (secrets masked) */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DocuSignConfigResponse(
            boolean enabled,
            // config fields
            String baseUrl,
            String authServer,
            String accountId,
            String integrationKey,
            String impersonatedUserId,
            // secret presence indicators — "*** saved ***" if set, else null
            String rsaPrivateKey,
            String webhookHmacSecret,
            // test status
            String testStatus,
            OffsetDateTime testedAt
    ) {}

    /** Request: update DocuSign config */
    public record DocuSignConfigRequest(
            boolean enabled,
            String baseUrl,
            String authServer,
            String accountId,
            String integrationKey,
            String impersonatedUserId,
            /** null or "*** saved ***" → keep existing; any other value → encrypt and store */
            String rsaPrivateKey,
            /** null or "*** saved ***" → keep existing; any other value → encrypt and store */
            String webhookHmacSecret
    ) {}

    /** Response from POST /test */
    public record TestConnectionResponse(
            boolean success,
            String message
    ) {}

    public static String masked(Object value) {
        return (value != null && !String.valueOf(value).isBlank()) ? MASKED : null;
    }

    public static boolean shouldUpdateSecret(String incomingValue) {
        return incomingValue != null
                && !incomingValue.isBlank()
                && !incomingValue.equals(MASKED);
    }
}
