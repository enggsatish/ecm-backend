package com.ecm.ocr.aigateway;

import com.ecm.common.client.AdminServiceClient;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Caches AI Gateway integration configuration fetched from ecm-admin.
 *
 * <p>Config includes the base URL for {@code /api/invoke}, Okta client_id +
 * client_secret for service JWT acquisition, and the routing mode
 * ({@code direct} or {@code gateway}) that governs whether LlamaTextEngine /
 * GlmOcrEngine call the gateway or stay on direct Ollama.
 *
 * <p>Cache TTL: 60 seconds. After an admin changes settings via the ECM
 * Integrations admin UI, ecm-ocr picks up the change within a minute without
 * a restart. Short enough to feel responsive, long enough to avoid hammering
 * ecm-admin on every OCR request.
 *
 * <p>On transient DB/HTTP errors, the cache returns the last-known-good value
 * if we have one, to keep OCR flowing during admin-service blips.
 */
@Slf4j
@Service
public class AiGatewayConfigService {

    private static final long CACHE_TTL_MS = 60_000L;

    private final AdminServiceClient adminServiceClient;

    private volatile CachedConfig cachedConfig;
    private volatile long cachedAt;

    public AiGatewayConfigService(AdminServiceClient adminServiceClient) {
        this.adminServiceClient = adminServiceClient;
    }

    /**
     * Returns the current AI Gateway config. Uses cached value within TTL, refreshes
     * otherwise. Falls back to the stale cache on fetch failure. Returns a "disabled"
     * config (route=direct, no creds) if nothing has ever been successfully fetched.
     */
    public CachedConfig get() {
        long now = System.currentTimeMillis();
        CachedConfig cached = cachedConfig;
        if (cached != null && (now - cachedAt) < CACHE_TTL_MS) {
            return cached;
        }

        try {
            Map<String, Object> raw = adminServiceClient.getAiGatewayOcrCredentials();
            CachedConfig fresh = fromMap(raw);
            cachedConfig = fresh;
            cachedAt = now;
            log.debug("AI Gateway config refreshed: route={}, baseUrlConfigured={}, credsConfigured={}",
                    fresh.route(), fresh.baseUrl() != null, fresh.hasServiceCredentials());
            return fresh;
        } catch (Exception e) {
            log.warn("Failed to refresh AI Gateway config: {}", e.getMessage());
            if (cached != null) return cached;
            // First-ever fetch failed — return a safe-default "disabled" config
            return CachedConfig.disabled();
        }
    }

    /**
     * Returns true if the pipeline should route OCR LLM calls through the AI Gateway.
     * Requires: route=gateway AND all credentials configured AND base URL set.
     * If any prerequisite is missing, returns false (stays on direct Ollama path).
     */
    public boolean shouldRouteViaGateway() {
        CachedConfig cfg = get();
        if (!"gateway".equalsIgnoreCase(cfg.route())) return false;
        if (cfg.baseUrl() == null || cfg.baseUrl().isBlank()) {
            log.warn("OCR route is 'gateway' but base URL not configured — falling back to direct");
            return false;
        }
        if (!cfg.hasServiceCredentials()) {
            log.warn("OCR route is 'gateway' but Okta client credentials not configured — falling back to direct");
            return false;
        }
        return true;
    }

    /** Forces the next {@link #get()} call to re-fetch from ecm-admin. */
    public void invalidateCache() {
        cachedAt = 0;
    }

    private CachedConfig fromMap(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) return CachedConfig.disabled();
        String baseUrl       = str(raw.get("baseUrl"));
        String oktaClientId  = str(raw.get("oktaClientId"));
        String oktaClientSec = str(raw.get("oktaClientSecret"));
        String oktaScope     = str(raw.get("oktaScope"));
        String route         = str(raw.get("route"));
        return new CachedConfig(
                route != null ? route : "direct",
                baseUrl,
                oktaClientId,
                oktaClientSec,
                oktaScope);
    }

    private static String str(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isBlank() ? null : s;
    }

    /** Immutable snapshot of AI Gateway config at a point in time. */
    @Getter
    public static class CachedConfig {
        private final String route;
        private final String baseUrl;
        private final String oktaClientId;
        private final String oktaClientSecret;
        private final String oktaScope;

        public CachedConfig(String route, String baseUrl, String oktaClientId,
                            String oktaClientSecret, String oktaScope) {
            this.route = route;
            this.baseUrl = baseUrl;
            this.oktaClientId = oktaClientId;
            this.oktaClientSecret = oktaClientSecret;
            this.oktaScope = oktaScope;
        }

        public String route()            { return route; }
        public String baseUrl()          { return baseUrl; }
        public String oktaClientId()     { return oktaClientId; }
        public String oktaClientSecret() { return oktaClientSecret; }
        public String oktaScope()        { return oktaScope; }

        public boolean hasServiceCredentials() {
            return oktaClientId != null && !oktaClientId.isBlank()
                    && oktaClientSecret != null && !oktaClientSecret.isBlank();
        }

        public static CachedConfig disabled() {
            return new CachedConfig("direct", null, null, null, null);
        }
    }
}
