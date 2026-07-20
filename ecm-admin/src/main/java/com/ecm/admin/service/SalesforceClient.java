package com.ecm.admin.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Map;

/**
 * Live Salesforce client — OAuth 2.0 Client Credentials flow + SOQL queries.
 * No data is synced or persisted; every call hits Salesforce directly (see
 * design note "CRM-Aware Form Fill & Customer 360" (2026-07-17) — "fully live
 * lookup" was the deliberate choice over a sync job).
 *
 * Credentials come from IntegrationConfigService (system_key=SALESFORCE),
 * the same encrypted-at-rest store DocuSign/AI Gateway use.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SalesforceClient {

    private static final String TENANT = "default";
    private static final String API_VERSION = "v59.0";
    // Client-credentials tokens commonly last ~2h; refresh well before that
    // so we never serve a request with a token about to expire mid-flight.
    private static final long TOKEN_TTL_MS = 25 * 60_000L;

    private final IntegrationConfigService integrationConfigService;
    private final RestTemplate restTemplate = new RestTemplate();

    private volatile String cachedAccessToken;
    private volatile String cachedInstanceUrl;
    private volatile long cachedTokenAt;

    public static class SalesforceNotConfiguredException extends RuntimeException {
        public SalesforceNotConfiguredException(String message) { super(message); }
    }

    /** True only when enabled AND all required config/secret fields are present. */
    public boolean isConfigured() {
        if (!integrationConfigService.isSalesforceEnabled(TENANT)) return false;
        return integrationConfigService.getSalesforceConfigField(TENANT, "login_url") != null
                && integrationConfigService.getSalesforceConfigField(TENANT, "client_id") != null
                && integrationConfigService.getSalesforceSecret(TENANT, "client_secret") != null;
    }

    /** The configured field used to match a party's external_id against a Salesforce record. */
    public String getContactLookupField() {
        return integrationConfigService.getSalesforceConfigField(TENANT, "contact_lookup_field");
    }

    /**
     * Runs a SOQL query and returns the raw "records" list from the response
     * (each record is a Map of field name → value, as Salesforce returns it).
     * Does not paginate — callers should keep queries scoped to one customer.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> query(String soql) {
        ensureToken();

        String url = UriComponentsBuilder.fromUriString(cachedInstanceUrl + "/services/data/" + API_VERSION + "/query")
                .queryParam("q", soql)
                .build()
                .toUriString();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(cachedAccessToken);
        try {
            Map<String, Object> body = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), Map.class).getBody();
            Object records = body != null ? body.get("records") : null;
            return records instanceof List ? (List<Map<String, Object>>) records : List.of();
        } catch (Exception e) {
            log.warn("[Salesforce] Query failed: {}", e.getMessage());
            throw new RuntimeException("Salesforce query failed: " + e.getMessage(), e);
        }
    }

    /** Forces a fresh token fetch — used by the admin "Test connection" action. */
    public synchronized void testAuthenticate() {
        cachedAccessToken = null;
        ensureToken();
    }

    private synchronized void ensureToken() {
        long now = System.currentTimeMillis();
        if (cachedAccessToken != null && (now - cachedTokenAt) < TOKEN_TTL_MS) return;
        authenticate();
    }

    private void authenticate() {
        if (!isConfigured()) {
            throw new SalesforceNotConfiguredException(
                    "Salesforce is not configured — set it up under Admin → Integrations → Salesforce");
        }
        String loginUrl = integrationConfigService.getSalesforceConfigField(TENANT, "login_url");
        // Strip trailing slash(es) — "https://x.my.salesforce.com/" + "/services/..." would
        // otherwise produce a double slash, which Salesforce may redirect in a way that
        // silently degrades the POST (losing the grant_type body) instead of erroring cleanly.
        if (loginUrl != null) loginUrl = loginUrl.trim().replaceAll("/+$", "");
        // Trim both — a copy-pasted secret/id with trailing whitespace or a newline
        // (easy to pick up when selecting text from a browser field or terminal)
        // fails as "invalid_client" with no hint that whitespace was the cause.
        String clientId = integrationConfigService.getSalesforceConfigField(TENANT, "client_id");
        if (clientId != null) clientId = clientId.trim();
        String clientSecret = integrationConfigService.getSalesforceSecret(TENANT, "client_secret");
        if (clientSecret != null) clientSecret = clientSecret.trim();

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        Map<?, ?> response;
        try {
            response = restTemplate.postForObject(
                    loginUrl + "/services/oauth2/token",
                    new HttpEntity<>(form, headers),
                    Map.class);
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            // clientId only — never log the secret. Lets you diff against the
            // Consumer Key shown in Salesforce Setup -> App Manager without
            // needing to trust that what's saved here matches what you typed.
            log.error("[Salesforce] Token request rejected: url={}, clientId={}, status={}, body={}",
                    loginUrl, clientId, e.getStatusCode(), e.getResponseBodyAsString());
            throw new IllegalStateException(
                    "Salesforce rejected the token request (" + e.getStatusCode() + "): " + e.getResponseBodyAsString(), e);
        }

        if (response == null || response.get("access_token") == null || response.get("instance_url") == null) {
            log.error("[Salesforce] Unexpected token response from url={}: {}", loginUrl, response);
            throw new IllegalStateException("Salesforce token response missing access_token/instance_url — got: " + response);
        }
        cachedAccessToken = String.valueOf(response.get("access_token"));
        cachedInstanceUrl = String.valueOf(response.get("instance_url"));
        cachedTokenAt = System.currentTimeMillis();
        log.info("[Salesforce] Authenticated via client credentials, instance={}", cachedInstanceUrl);
    }

    /** Escapes a value for safe interpolation into a SOQL string literal. */
    public static String escapeSoql(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("'", "\\'");
    }
}
