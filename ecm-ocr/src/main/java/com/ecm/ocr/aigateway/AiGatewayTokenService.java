package com.ecm.ocr.aigateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * Obtains and caches Okta service JWTs via OAuth2 client_credentials flow.
 *
 * <p>The token is used in the {@code Authorization: Bearer <jwt>} header on every
 * AI Gateway {@code /api/invoke} call. The gateway resolves the {@code cid} claim
 * from the token to the {@code ecm-ocr-pipeline} application row, then applies
 * tenant policies, quotas, and model allowlists accordingly.
 *
 * <h3>Cache behavior</h3>
 * <ul>
 *   <li>Tokens are cached until {@code expires_in - 60s} (safety margin)</li>
 *   <li>Double-checked locking under {@code synchronized} ensures one refresh at a time
 *       even under RabbitMQ consumer concurrency</li>
 *   <li>On refresh failure, the old token is cleared — next call will retry the fetch</li>
 *   <li>{@link #invalidateToken()} forces a refresh on next call (e.g. after a 401 from the gateway)</li>
 * </ul>
 *
 * <h3>Okta endpoint</h3>
 * Reuses {@code okta.issuer-uri} from ecm-ocr's existing Spring config and appends
 * {@code /v1/token} to derive the token endpoint. This avoids duplicating Okta
 * configuration — the same issuer that validates user JWTs also issues service JWTs.
 */
@Slf4j
@Service
public class AiGatewayTokenService {

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);
    private static final long REFRESH_SAFETY_MARGIN_MS = 60_000L;
    // Fallback if Okta doesn't return expires_in — conservative 5-minute TTL
    private static final long DEFAULT_TTL_MS = 5 * 60_000L;

    private final AiGatewayConfigService configService;
    private final ObjectMapper objectMapper;
    private final String oktaIssuerUri;
    private final HttpClient httpClient;

    private volatile String cachedToken;
    private volatile long cachedTokenExpiresAt;

    public AiGatewayTokenService(AiGatewayConfigService configService,
                                 ObjectMapper objectMapper,
                                 @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") String oktaIssuerUri) {
        this.configService = configService;
        this.objectMapper = objectMapper;
        this.oktaIssuerUri = oktaIssuerUri;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * Returns a valid service JWT, refreshing from Okta if the cached one is expired
     * or missing. Returns {@code null} if credentials are not configured — caller
     * should treat this as "cannot route via gateway" and fall back to direct Ollama.
     *
     * @throws AiGatewayTokenException if token acquisition fails after cache miss
     */
    public String getServiceToken() {
        long now = System.currentTimeMillis();
        String token = cachedToken;
        if (token != null && now < cachedTokenExpiresAt) {
            return token;
        }

        synchronized (this) {
            // Double-check after acquiring the lock — another thread may have refreshed
            token = cachedToken;
            if (token != null && System.currentTimeMillis() < cachedTokenExpiresAt) {
                return token;
            }
            return refreshToken();
        }
    }

    /**
     * Forces the next {@link #getServiceToken()} call to fetch a fresh token from Okta.
     * Call this after a 401 response from the AI Gateway to recover from a token that
     * was revoked mid-flight.
     */
    public void invalidateToken() {
        synchronized (this) {
            cachedToken = null;
            cachedTokenExpiresAt = 0;
        }
    }

    private String refreshToken() {
        AiGatewayConfigService.CachedConfig cfg = configService.get();
        if (!cfg.hasServiceCredentials()) {
            log.debug("AI Gateway service credentials not configured — token refresh skipped");
            return null;
        }
        if (oktaIssuerUri == null || oktaIssuerUri.isBlank()) {
            log.warn("okta.oauth2.issuer is not configured — cannot acquire AI Gateway service token");
            return null;
        }

        String tokenUrl = oktaIssuerUri.replaceAll("/$", "") + "/v1/token";
        String basicAuth = Base64.getEncoder().encodeToString(
                (cfg.oktaClientId() + ":" + cfg.oktaClientSecret()).getBytes(StandardCharsets.UTF_8));
        String body = "grant_type=client_credentials"; // no scope → Okta returns token with default scopes

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(tokenUrl))
                    .header("Authorization", "Basic " + basicAuth)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(HTTP_TIMEOUT)
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("Okta token endpoint returned HTTP {}: {}",
                        response.statusCode(), truncate(response.body(), 300));
                throw new AiGatewayTokenException(
                        "Okta token endpoint returned HTTP " + response.statusCode());
            }

            JsonNode root = objectMapper.readTree(response.body());
            String accessToken = root.path("access_token").asText(null);
            long expiresIn = root.path("expires_in").asLong(0);

            if (accessToken == null || accessToken.isBlank()) {
                log.warn("Okta token response missing access_token field");
                throw new AiGatewayTokenException("Okta token response missing access_token");
            }

            long ttlMs = (expiresIn > 0 ? expiresIn * 1000L : DEFAULT_TTL_MS) - REFRESH_SAFETY_MARGIN_MS;
            if (ttlMs < 10_000L) ttlMs = 10_000L; // minimum 10s so we don't thrash

            cachedToken = accessToken;
            cachedTokenExpiresAt = System.currentTimeMillis() + ttlMs;

            log.info("Obtained AI Gateway service JWT, ttl={}s, expiresAt={}",
                    ttlMs / 1000, cachedTokenExpiresAt);
            return accessToken;

        } catch (AiGatewayTokenException e) {
            cachedToken = null;
            cachedTokenExpiresAt = 0;
            throw e;
        } catch (Exception e) {
            cachedToken = null;
            cachedTokenExpiresAt = 0;
            log.warn("Failed to acquire AI Gateway service JWT: {}", e.getMessage());
            throw new AiGatewayTokenException("Failed to acquire service JWT: " + e.getMessage(), e);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    /** Thrown when service token acquisition fails. Caller should fall back to direct Ollama. */
    public static class AiGatewayTokenException extends RuntimeException {
        public AiGatewayTokenException(String message) { super(message); }
        public AiGatewayTokenException(String message, Throwable cause) { super(message, cause); }
    }
}
