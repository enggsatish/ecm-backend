package com.ecm.eforms.service;

import com.ecm.common.util.EncryptionUtil;
import com.ecm.eforms.model.entity.FormSubmission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * DocuSign Integration Service — Sprint 2.
 *
 * Reads live config from ecm_admin.integration_configs via cross-schema JdbcTemplate.
 * Secrets are stored AES-GCM encrypted in the DB; this service reads the encrypted value
 * and delegates decryption to IntegrationConfigService (ecm-admin) via the same DB query
 * — note: this service can only decrypt if it has the same master key.
 *
 * For Sprint 2 stub mode:
 *   - Set ecm.docusign.enabled=false (or leave integration disabled in admin UI)
 *   - All operations log and return stub values
 *
 * For Sprint 2 live mode:
 *   1. Enable DocuSign in Admin UI → /admin/integrations/docusign
 *   2. Add DocuSign Java SDK to ecm-eforms pom.xml:
 *      <dependency><groupId>com.docusign</groupId><artifactId>docusign-esign-java</artifactId>
 *                  <version>4.4.0</version></dependency>
 *   3. Uncomment the live blocks below.
 *
 * Cross-schema DB access pattern:
 *   ecm-eforms connects to the same ecmdb PostgreSQL instance.
 *   Reading from ecm_admin schema is allowed (read-only cross-schema access).
 *   Writing to another module's schema uses JdbcTemplate only, never JPA.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocuSignService {

    private final JdbcTemplate jdbcTemplate;

    // ── Config helpers ────────────────────────────────────────────────────────

    private Map<String, Object> getDocuSignConfig() {
        try {
            return jdbcTemplate.queryForMap(
                "SELECT enabled, config, secrets FROM ecm_admin.integration_configs " +
                "WHERE tenant_id = 'default' AND system_name = 'DOCUSIGN'");
        } catch (Exception e) {
            log.warn("[DocuSign] Could not read integration config: {}", e.getMessage());
            return Map.of("enabled", false, "config", "{}", "secrets", "{}");
        }
    }

    private boolean isEnabled() {
        try {
            Boolean enabled = (Boolean) getDocuSignConfig().get("enabled");
            return Boolean.TRUE.equals(enabled);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Reads a JSONB config field from the stored integration_configs row.
     * Uses PostgreSQL's -> operator via a targeted query for safety.
     */
    private String getConfigField(String field) {
        try {
            return jdbcTemplate.queryForObject(
                "SELECT config->>? FROM ecm_admin.integration_configs " +
                "WHERE tenant_id = 'default' AND system_name = 'DOCUSIGN'",
                String.class, field);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Reads and decrypts an AES-GCM encrypted secret field.
     *
     * Note: Decryption requires the same MASTER_ENCRYPT_KEY that ecm-admin used to encrypt.
     * Both services should be configured with the same ecm.master-encrypt-key env var.
     */
    private String getSecretField(String field) {
        try {
            String encrypted = jdbcTemplate.queryForObject(
                "SELECT secrets->>? FROM ecm_admin.integration_configs " +
                "WHERE tenant_id = 'default' AND system_name = 'DOCUSIGN'",
                String.class, field);
            if (encrypted == null || encrypted.isBlank()) return null;
            return EncryptionUtil.decryptAesGcm(encrypted);
        } catch (Exception e) {
            log.warn("[DocuSign] Could not read secret field {}: {}", field, e.getMessage());
            return null;
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Creates a DocuSign signing envelope for the given submission.
     *
     * @return the DocuSign envelopeId (stub: UUID string when integration is disabled)
     */
    public String createEnvelope(FormSubmission submission) {
        if (!isEnabled()) {
            String stubId = "STUB-ENVELOPE-" + UUID.randomUUID();
            log.info("[DocuSign STUB] createEnvelope: submissionId={}, stubEnvelopeId={}",
                submission.getId(), stubId);
            return stubId;
        }

        String integrationKey     = getConfigField("integration_key");
        String accountId          = getConfigField("account_id");
        String authServer         = getConfigField("auth_server");
        String baseUrl            = getConfigField("base_url");
        String impersonatedUserId = getConfigField("impersonated_user_id");
        String rsaPrivateKey      = getSecretField("rsa_private_key");

        if (integrationKey == null || accountId == null || rsaPrivateKey == null) {
            log.error("[DocuSign] Missing required configuration — cannot create envelope");
            throw new IllegalStateException("DocuSign integration is not fully configured");
        }

        // ── LIVE implementation — uncomment when SDK is on classpath ──────
        // try {
        //     ApiClient apiClient = new ApiClient(authServer);
        //     byte[] keyBytes = rsaPrivateKey.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        //     OAuthToken token = apiClient.requestJWTUserToken(
        //         integrationKey, impersonatedUserId,
        //         java.util.List.of("signature", "impersonation"), keyBytes, 3600);
        //     apiClient.setAccessToken(token.getAccessToken(), token.getExpiresIn());
        //     apiClient.setBasePath(baseUrl);
        //
        //     // Build envelope definition
        //     EnvelopeDefinition env = buildEnvelope(submission);
        //     EnvelopesApi api = new EnvelopesApi(apiClient);
        //     EnvelopeSummary summary = api.createEnvelope(accountId, env);
        //     log.info("[DocuSign] Envelope created: {}", summary.getEnvelopeId());
        //     return summary.getEnvelopeId();
        // } catch (Exception e) {
        //     log.error("[DocuSign] createEnvelope failed: {}", e.getMessage(), e);
        //     throw new RuntimeException("DocuSign createEnvelope failed", e);
        // }

        // Stub: config verified but SDK not wired
        String stubId = "LIVE-STUB-" + UUID.randomUUID();
        log.info("[DocuSign LIVE-STUB] Config verified. Returning stub envelopeId={}", stubId);
        return stubId;
    }

    /**
     * Downloads the completed signed document from DocuSign.
     *
     * @return raw PDF bytes (empty byte array in stub mode)
     */
    public byte[] downloadSignedDocument(String envelopeId) {
        if (!isEnabled() || envelopeId.startsWith("STUB-")) {
            log.info("[DocuSign STUB] downloadSignedDocument: envelopeId={}", envelopeId);
            return new byte[0];
        }

        // ── LIVE implementation ─────────────────────────────────────────
        // EnvelopesApi api = buildApi();
        // EnvelopeDocumentsResult docs = api.listDocuments(accountId, envelopeId, null);
        // String docId = docs.getEnvelopeDocuments().get(0).getDocumentId();
        // return api.getDocument(accountId, envelopeId, docId);

        log.info("[DocuSign LIVE-STUB] downloadSignedDocument envelopeId={}", envelopeId);
        return new byte[0];
    }

    /**
     * Validates the HMAC signature on a DocuSign Connect webhook event.
     *
     * @param rawBody    the raw request body bytes
     * @param hmacHeader the X-DocuSign-Signature-1 header value
     * @throws SecurityException if validation fails
     */
    public void validateWebhookHmac(byte[] rawBody, String hmacHeader) {
        if (hmacHeader == null || hmacHeader.isBlank()) {
            throw new SecurityException("Missing X-DocuSign-Signature-1 header");
        }

        String secret = getSecretField("webhook_hmac_secret");
        if (secret == null || secret.isBlank()) {
            log.warn("[DocuSign Webhook] HMAC secret not configured — skipping validation (NOT for production)");
            return;
        }

        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expectedBytes = mac.doFinal(rawBody);
            String expected = java.util.Base64.getEncoder().encodeToString(expectedBytes);

            if (!constantTimeEquals(expected, hmacHeader)) {
                throw new SecurityException("DocuSign HMAC signature validation failed");
            }
            log.debug("[DocuSign Webhook] HMAC validation passed");
        } catch (SecurityException se) {
            throw se;
        } catch (Exception e) {
            throw new SecurityException("HMAC validation error: " + e.getMessage(), e);
        }
    }

    /** Timing-safe string comparison to prevent timing attacks. */
    private boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }

    public void voidEnvelope(String envelopeId, String reason) {
        log.info("[DocuSign] voidEnvelope: envelopeId={}, reason={}", envelopeId, reason);
        // LIVE: EnvelopesApi.update() with Envelope.status="voided"
    }

    public void resendEnvelope(String envelopeId) {
        log.info("[DocuSign] resendEnvelope: envelopeId={}", envelopeId);
        // LIVE: EnvelopesApi.update() with resend_envelope=true
    }

}
