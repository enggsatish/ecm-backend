package com.ecm.admin.dto;

import java.time.OffsetDateTime;

/**
 * DTO for the AI Gateway integration settings.
 *
 * <p>Used for GET/PUT on {@code /api/admin/integrations/ai-gateway}.
 *
 * <h3>Secret handling:</h3>
 * <p>The HMAC secret is <b>write-only</b> from the API perspective. Reads never
 * return the plaintext value — only a {@code configured} flag, a masked preview
 * (last 4 chars), and the last-updated timestamp. This matches how the AI
 * Gateway itself treats its webhook secret, and prevents admins from being able
 * to exfiltrate secrets via the read endpoint.
 *
 * <p>Writes: to update the URL without touching the secret, send {@code hmacSecret=null}
 * (or omit the field). To rotate the secret, send the new plaintext value.
 */
public class AiGatewayIntegrationDto {

    /** Response shape — returned on GET and PUT. */
    public static class Response {
        private String url;
        private boolean hmacSecretConfigured;
        private String hmacSecretPreview;       // e.g. "••••1a2b" — last 4 chars only
        private OffsetDateTime hmacSecretUpdatedAt;

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }

        public boolean isHmacSecretConfigured() { return hmacSecretConfigured; }
        public void setHmacSecretConfigured(boolean hmacSecretConfigured) { this.hmacSecretConfigured = hmacSecretConfigured; }

        public String getHmacSecretPreview() { return hmacSecretPreview; }
        public void setHmacSecretPreview(String hmacSecretPreview) { this.hmacSecretPreview = hmacSecretPreview; }

        public OffsetDateTime getHmacSecretUpdatedAt() { return hmacSecretUpdatedAt; }
        public void setHmacSecretUpdatedAt(OffsetDateTime hmacSecretUpdatedAt) { this.hmacSecretUpdatedAt = hmacSecretUpdatedAt; }
    }

    /** PUT request body. Nulls mean "leave unchanged". */
    public static class UpdateRequest {
        private String url;
        private String hmacSecret;

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }

        public String getHmacSecret() { return hmacSecret; }
        public void setHmacSecret(String hmacSecret) { this.hmacSecret = hmacSecret; }
    }
}
