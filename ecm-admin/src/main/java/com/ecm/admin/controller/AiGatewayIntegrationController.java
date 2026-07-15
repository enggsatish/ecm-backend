package com.ecm.admin.controller;

import com.ecm.admin.dto.AiGatewayIntegrationDto;
import com.ecm.admin.entity.IntegrationConfig;
import com.ecm.admin.entity.TenantConfig;
import com.ecm.admin.repository.IntegrationConfigRepository;
import com.ecm.admin.repository.TenantConfigRepository;
import com.ecm.admin.service.IntegrationConfigService;
import com.ecm.common.model.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Admin API for the AI Gateway integration — webhook URL + HMAC secret (Change 2)
 * plus base URL, Okta service credentials, and OCR routing mode (Phase 2a).
 *
 * <h3>Storage split</h3>
 * Non-sensitive fields live in {@code ecm_admin.tenant_config}:
 * <ul>
 *   <li>{@code ai.gateway.webhook.url}</li>
 *   <li>{@code ai.gateway.webhook.hmac.secret} (plaintext — legacy from Change 2)</li>
 *   <li>{@code ai.gateway.base.url}</li>
 *   <li>{@code ai.gateway.okta.client.id}</li>
 *   <li>{@code ecm.ocr.route} (values: {@code direct} or {@code gateway})</li>
 * </ul>
 *
 * The Okta client_secret lives in {@code ecm_admin.integration_configs} with
 * {@code system_key='AI_GATEWAY_OCR'}, AES-GCM encrypted via
 * {@link IntegrationConfigService} using the {@code ecm.master-encrypt-key} env var.
 * This matches the DocuSign credential pattern already in use.
 *
 * <h3>Secret semantics</h3>
 * Both {@code hmacSecret} and {@code oktaClientSecret} are write-only from the API:
 * reads return only a configured flag, a masked preview (last 4 chars), and the
 * last-updated timestamp. Sending {@code null} on write means "leave unchanged".
 */
@RestController
@RequestMapping("/api/admin/integrations/ai-gateway")
public class AiGatewayIntegrationController {

    private static final Logger log = LoggerFactory.getLogger(AiGatewayIntegrationController.class);

    private static final String KEY_WEBHOOK_URL    = "ai.gateway.webhook.url";
    private static final String KEY_HMAC_SECRET    = "ai.gateway.webhook.hmac.secret";
    private static final String KEY_BASE_URL       = "ai.gateway.base.url";
    private static final String KEY_OKTA_CLIENT_ID = "ai.gateway.okta.client.id";
    private static final String KEY_OKTA_SCOPE     = "ai.gateway.okta.scope";
    private static final String KEY_OCR_ROUTE      = "ecm.ocr.route";

    private static final String INTEGRATION_SYSTEM_KEY = "AI_GATEWAY_OCR";
    private static final String SECRET_FIELD_OKTA_CLIENT_SECRET = "okta_client_secret";

    private static final String DEFAULT_TENANT = "default";
    private static final String DEFAULT_ROUTE  = "direct";

    private final TenantConfigRepository tenantConfigRepo;
    private final IntegrationConfigRepository integrationConfigRepo;
    private final IntegrationConfigService integrationConfigService;

    public AiGatewayIntegrationController(TenantConfigRepository tenantConfigRepo,
                                          IntegrationConfigRepository integrationConfigRepo,
                                          IntegrationConfigService integrationConfigService) {
        this.tenantConfigRepo = tenantConfigRepo;
        this.integrationConfigRepo = integrationConfigRepo;
        this.integrationConfigService = integrationConfigService;
    }

    @GetMapping
    @PreAuthorize("hasPermission(null, 'admin:configure')")
    public ResponseEntity<ApiResponse<AiGatewayIntegrationDto.Response>> get() {
        return ResponseEntity.ok(ApiResponse.ok(buildResponse()));
    }

    @PutMapping
    @Transactional
    @PreAuthorize("hasPermission(null, 'admin:configure')")
    public ResponseEntity<ApiResponse<AiGatewayIntegrationDto.Response>> update(
            @RequestBody AiGatewayIntegrationDto.UpdateRequest req) {

        // Non-sensitive fields — all in tenant_config, null = leave unchanged
        if (req.getUrl() != null) {
            upsertTenantConfig(KEY_WEBHOOK_URL, req.getUrl().trim(),
                    "AI Gateway webhook endpoint for OCR RAG push (e.g. http://localhost:8090/api/webhook/rag-ingest)");
        }
        if (req.getBaseUrl() != null) {
            upsertTenantConfig(KEY_BASE_URL, req.getBaseUrl().trim(),
                    "AI Gateway base URL for /api/invoke calls (e.g. http://localhost:8090)");
        }
        if (req.getOktaClientId() != null) {
            upsertTenantConfig(KEY_OKTA_CLIENT_ID, req.getOktaClientId().trim(),
                    "Okta API Services client_id for ecm-ocr-pipeline service JWT");
        }
        if (req.getOktaScope() != null) {
            upsertTenantConfig(KEY_OKTA_SCOPE, req.getOktaScope().trim(),
                    "OAuth2 scope(s) requested in the client_credentials token call (space-separated if multiple)");
        }
        if (req.getRoute() != null) {
            String route = req.getRoute().trim().toLowerCase();
            if (!route.equals("direct") && !route.equals("gateway")) {
                return ResponseEntity.badRequest().body(
                        ApiResponse.error("route must be 'direct' or 'gateway', got: " + route, "INVALID_ROUTE"));
            }
            upsertTenantConfig(KEY_OCR_ROUTE, route,
                    "OCR LLM routing mode: 'direct' = direct to Ollama, 'gateway' = via AI Gateway /api/invoke");
            log.info("OCR routing mode changed to '{}' by admin", route);
        }

        // HMAC secret — null/blank means leave unchanged (Change 2 legacy plaintext storage)
        if (req.getHmacSecret() != null && !req.getHmacSecret().isBlank()) {
            upsertTenantConfig(KEY_HMAC_SECRET, req.getHmacSecret().trim(),
                    "HMAC-SHA256 shared secret for signing AI Gateway webhook calls");
            log.info("AI Gateway webhook HMAC secret updated by admin");
        }

        // Okta client_secret — encrypted at rest in integration_configs
        if (req.getOktaClientSecret() != null && !req.getOktaClientSecret().isBlank()) {
            upsertOktaClientSecret(req.getOktaClientSecret().trim());
            log.info("AI Gateway Okta client_secret updated by admin");
        }

        return ResponseEntity.ok(ApiResponse.ok(buildResponse(), "AI Gateway integration saved"));
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private void upsertTenantConfig(String key, String value, String description) {
        TenantConfig tc = tenantConfigRepo.findById(key).orElseGet(() -> {
            TenantConfig n = new TenantConfig();
            n.setKey(key);
            return n;
        });
        tc.setValue(value);
        if (tc.getDescription() == null || tc.getDescription().isBlank()) {
            tc.setDescription(description);
        }
        tc.setUpdatedAt(OffsetDateTime.now());
        tenantConfigRepo.save(tc);
    }

    private void upsertOktaClientSecret(String plaintext) {
        IntegrationConfig cfg = integrationConfigRepo
                .findByTenantIdAndSystemKey(DEFAULT_TENANT, INTEGRATION_SYSTEM_KEY)
                .orElseGet(() -> {
                    IntegrationConfig row = IntegrationConfig.builder()
                            .tenantId(DEFAULT_TENANT)
                            .systemKey(INTEGRATION_SYSTEM_KEY)
                            .displayName("AI Gateway (OCR)")
                            .enabled(true)
                            .config(new HashMap<>())
                            .secrets(new HashMap<>())
                            .build();
                    // test_status has a NOT NULL constraint in integration_configs — seed with
                    // "UNTESTED" on row creation. DocuSign avoids this via recordTestResult(),
                    // but we don't have a connection-test flow yet.
                    row.setTestStatus("UNTESTED");
                    return row;
                });
        Map<String, Object> secrets = new HashMap<>(cfg.getSecrets() != null ? cfg.getSecrets() : new HashMap<>());
        secrets.put(SECRET_FIELD_OKTA_CLIENT_SECRET, integrationConfigService.encrypt(plaintext));
        cfg.setSecrets(secrets);
        cfg.setUpdatedAt(OffsetDateTime.now());
        integrationConfigRepo.save(cfg);
    }

    private AiGatewayIntegrationDto.Response buildResponse() {
        AiGatewayIntegrationDto.Response out = new AiGatewayIntegrationDto.Response();

        // tenant_config fields
        readTenantConfig(KEY_WEBHOOK_URL).ifPresent(out::setUrl);
        readTenantConfig(KEY_BASE_URL).ifPresent(out::setBaseUrl);
        readTenantConfig(KEY_OKTA_CLIENT_ID).ifPresent(out::setOktaClientId);
        readTenantConfig(KEY_OKTA_SCOPE).ifPresent(out::setOktaScope);
        out.setRoute(readTenantConfig(KEY_OCR_ROUTE).orElse(DEFAULT_ROUTE));

        // HMAC secret (tenant_config, plaintext at rest, masked on read)
        Optional<TenantConfig> hmacRow = tenantConfigRepo.findById(KEY_HMAC_SECRET);
        if (hmacRow.isPresent() && hmacRow.get().getValue() != null && !hmacRow.get().getValue().isBlank()) {
            TenantConfig tc = hmacRow.get();
            out.setHmacSecretConfigured(true);
            out.setHmacSecretPreview(maskPreview(tc.getValue()));
            out.setHmacSecretUpdatedAt(tc.getUpdatedAt());
        } else {
            out.setHmacSecretConfigured(false);
        }

        // Okta client_secret (integration_configs, encrypted at rest, masked on read)
        Optional<IntegrationConfig> cfgRow = integrationConfigRepo
                .findByTenantIdAndSystemKey(DEFAULT_TENANT, INTEGRATION_SYSTEM_KEY);
        if (cfgRow.isPresent() && cfgRow.get().getSecrets() != null
                && cfgRow.get().getSecrets().get(SECRET_FIELD_OKTA_CLIENT_SECRET) != null) {
            IntegrationConfig cfg = cfgRow.get();
            out.setOktaClientSecretConfigured(true);
            // We can't preview the ciphertext meaningfully, and decrypting just to mask leaks through logs.
            // Show a fixed placeholder; the admin UI renders it as "configured".
            out.setOktaClientSecretPreview("••••••••");
            out.setOktaClientSecretUpdatedAt(cfg.getUpdatedAt());
        } else {
            out.setOktaClientSecretConfigured(false);
        }

        return out;
    }

    private Optional<String> readTenantConfig(String key) {
        return tenantConfigRepo.findById(key)
                .map(TenantConfig::getValue)
                .filter(v -> v != null && !v.isBlank());
    }

    /** Returns a masked form of the secret showing only the last 4 chars. */
    private String maskPreview(String value) {
        if (value == null || value.length() <= 4) return "••••";
        return "••••" + value.substring(value.length() - 4);
    }
}
