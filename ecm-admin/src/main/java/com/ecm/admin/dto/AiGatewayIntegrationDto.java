package com.ecm.admin.dto;

import java.time.OffsetDateTime;

/**
 * DTO for the AI Gateway integration settings.
 *
 * <p>Used for GET/PUT on {@code /api/admin/integrations/ai-gateway}.
 *
 * <h3>Fields:</h3>
 * <ul>
 *   <li><b>webhookUrl</b> — AI Gateway RAG ingestion webhook endpoint (for ecm-ocr to push text after OCR)</li>
 *   <li><b>hmacSecret</b> — HMAC-SHA256 shared secret used to sign webhook calls</li>
 *   <li><b>baseUrl</b> — AI Gateway base URL for {@code /api/invoke} calls (e.g. {@code http://localhost:8090})</li>
 *   <li><b>oktaClientId</b> — Okta API Services client_id for ecm-ocr-pipeline service JWT</li>
 *   <li><b>oktaClientSecret</b> — Okta API Services client_secret (AES-GCM encrypted at rest)</li>
 *   <li><b>route</b> — routing mode for OCR LLM calls: {@code direct} or {@code gateway}</li>
 * </ul>
 *
 * <h3>Secret handling:</h3>
 * <p>Both {@code hmacSecret} and {@code oktaClientSecret} are <b>write-only</b>. Reads never
 * return the plaintext value — only a configured flag, a masked preview (last 4 chars), and
 * the last-updated timestamp.
 *
 * <p>Writes: to update other fields without touching a secret, send the secret field as null
 * (or omit it). To rotate a secret, send the new plaintext value.
 */
public class AiGatewayIntegrationDto {

    /** Response shape — returned on GET and PUT. Secrets are never echoed in plaintext. */
    public static class Response {
        // Webhook URL (used by ecm-ocr for RAG push after OCR completes) — existing from Change 2
        private String url;
        private boolean hmacSecretConfigured;
        private String hmacSecretPreview;
        private OffsetDateTime hmacSecretUpdatedAt;

        // Invoke base URL (used by ecm-ocr for /api/invoke calls during OCR) — new for Phase 2a
        private String baseUrl;

        // Okta API Services client for ecm-ocr-pipeline service JWT — new for Phase 2a
        private String oktaClientId;
        private boolean oktaClientSecretConfigured;
        private String oktaClientSecretPreview;
        private OffsetDateTime oktaClientSecretUpdatedAt;
        private String oktaScope;

        // Routing mode: "direct" (existing direct-to-Ollama path) or "gateway" (via AI Gateway)
        private String route;

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }

        public boolean isHmacSecretConfigured() { return hmacSecretConfigured; }
        public void setHmacSecretConfigured(boolean hmacSecretConfigured) { this.hmacSecretConfigured = hmacSecretConfigured; }

        public String getHmacSecretPreview() { return hmacSecretPreview; }
        public void setHmacSecretPreview(String hmacSecretPreview) { this.hmacSecretPreview = hmacSecretPreview; }

        public OffsetDateTime getHmacSecretUpdatedAt() { return hmacSecretUpdatedAt; }
        public void setHmacSecretUpdatedAt(OffsetDateTime hmacSecretUpdatedAt) { this.hmacSecretUpdatedAt = hmacSecretUpdatedAt; }

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

        public String getOktaClientId() { return oktaClientId; }
        public void setOktaClientId(String oktaClientId) { this.oktaClientId = oktaClientId; }

        public boolean isOktaClientSecretConfigured() { return oktaClientSecretConfigured; }
        public void setOktaClientSecretConfigured(boolean oktaClientSecretConfigured) { this.oktaClientSecretConfigured = oktaClientSecretConfigured; }

        public String getOktaClientSecretPreview() { return oktaClientSecretPreview; }
        public void setOktaClientSecretPreview(String oktaClientSecretPreview) { this.oktaClientSecretPreview = oktaClientSecretPreview; }

        public OffsetDateTime getOktaClientSecretUpdatedAt() { return oktaClientSecretUpdatedAt; }
        public void setOktaClientSecretUpdatedAt(OffsetDateTime oktaClientSecretUpdatedAt) { this.oktaClientSecretUpdatedAt = oktaClientSecretUpdatedAt; }

        public String getOktaScope() { return oktaScope; }
        public void setOktaScope(String oktaScope) { this.oktaScope = oktaScope; }

        public String getRoute() { return route; }
        public void setRoute(String route) { this.route = route; }
    }

    /** PUT request body. Nulls mean "leave unchanged". */
    public static class UpdateRequest {
        private String url;
        private String hmacSecret;
        private String baseUrl;
        private String oktaClientId;
        private String oktaClientSecret;
        private String oktaScope;
        private String route;

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }

        public String getHmacSecret() { return hmacSecret; }
        public void setHmacSecret(String hmacSecret) { this.hmacSecret = hmacSecret; }

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

        public String getOktaClientId() { return oktaClientId; }
        public void setOktaClientId(String oktaClientId) { this.oktaClientId = oktaClientId; }

        public String getOktaClientSecret() { return oktaClientSecret; }
        public void setOktaClientSecret(String oktaClientSecret) { this.oktaClientSecret = oktaClientSecret; }

        public String getOktaScope() { return oktaScope; }
        public void setOktaScope(String oktaScope) { this.oktaScope = oktaScope; }

        public String getRoute() { return route; }
        public void setRoute(String route) { this.route = route; }
    }

    /**
     * Internal shape returned to ecm-ocr via {@code /internal/admin/ai-gateway/ocr-credentials}.
     * This is the ONE place the plaintext Okta client_secret leaves ecm-admin,
     * and only to the internal network via {@code X-Internal-Service} header.
     */
    public static class InternalCredentials {
        private String baseUrl;
        private String oktaClientId;
        private String oktaClientSecret;
        private String oktaScope;
        private String route;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

        public String getOktaClientId() { return oktaClientId; }
        public void setOktaClientId(String oktaClientId) { this.oktaClientId = oktaClientId; }

        public String getOktaClientSecret() { return oktaClientSecret; }
        public void setOktaClientSecret(String oktaClientSecret) { this.oktaClientSecret = oktaClientSecret; }

        public String getOktaScope() { return oktaScope; }
        public void setOktaScope(String oktaScope) { this.oktaScope = oktaScope; }

        public String getRoute() { return route; }
        public void setRoute(String route) { this.route = route; }
    }
}
