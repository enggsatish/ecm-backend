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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal-only endpoint returning decrypted AI Gateway OCR credentials to ecm-ocr.
 *
 * <p>Protected by {@code AdminSecurityConfig}'s {@code X-Internal-Service} header check —
 * only callers in the whitelist (ecm-ocr, ecm-internal, ecm-batch) can reach this.
 *
 * <p>This is the ONE place the plaintext Okta client_secret leaves ecm-admin, and only
 * to the internal network. See {@code project_inter_service_auth_pattern.md}.
 *
 * <p>Read-only. No mutations. No {@code @PreAuthorize} — access is controlled by the
 * security filter's header whitelist.
 */
@RestController
@RequestMapping("/internal/admin/ai-gateway")
public class InternalAiGatewayController {

    private static final Logger log = LoggerFactory.getLogger(InternalAiGatewayController.class);

    private static final String KEY_BASE_URL       = "ai.gateway.base.url";
    private static final String KEY_OKTA_CLIENT_ID = "ai.gateway.okta.client.id";
    private static final String KEY_OCR_ROUTE      = "ecm.ocr.route";

    private static final String INTEGRATION_SYSTEM_KEY = "AI_GATEWAY_OCR";
    private static final String SECRET_FIELD_OKTA_CLIENT_SECRET = "okta_client_secret";

    private static final String DEFAULT_TENANT = "default";
    private static final String DEFAULT_ROUTE  = "direct";

    private final TenantConfigRepository tenantConfigRepo;
    private final IntegrationConfigRepository integrationConfigRepo;
    private final IntegrationConfigService integrationConfigService;

    public InternalAiGatewayController(TenantConfigRepository tenantConfigRepo,
                                       IntegrationConfigRepository integrationConfigRepo,
                                       IntegrationConfigService integrationConfigService) {
        this.tenantConfigRepo = tenantConfigRepo;
        this.integrationConfigRepo = integrationConfigRepo;
        this.integrationConfigService = integrationConfigService;
    }

    /**
     * Returns the AI Gateway integration credentials for ecm-ocr to obtain a service JWT
     * and call {@code /api/invoke}. The Okta client_secret is decrypted from AES-GCM
     * storage in {@code integration_configs} before returning.
     *
     * <p>Caller (ecm-ocr) should cache the result for ~60 seconds to avoid hammering
     * this endpoint on every OCR request.
     */
    @GetMapping("/ocr-credentials")
    public ResponseEntity<ApiResponse<AiGatewayIntegrationDto.InternalCredentials>> getOcrCredentials() {
        AiGatewayIntegrationDto.InternalCredentials out = new AiGatewayIntegrationDto.InternalCredentials();

        // Non-sensitive fields from tenant_config
        tenantConfigRepo.findById(KEY_BASE_URL)
                .map(TenantConfig::getValue)
                .filter(v -> v != null && !v.isBlank())
                .ifPresent(out::setBaseUrl);

        tenantConfigRepo.findById(KEY_OKTA_CLIENT_ID)
                .map(TenantConfig::getValue)
                .filter(v -> v != null && !v.isBlank())
                .ifPresent(out::setOktaClientId);

        out.setRoute(tenantConfigRepo.findById(KEY_OCR_ROUTE)
                .map(TenantConfig::getValue)
                .filter(v -> v != null && !v.isBlank())
                .orElse(DEFAULT_ROUTE));

        // Sensitive field from integration_configs (decrypt on read)
        IntegrationConfig cfg = integrationConfigRepo
                .findByTenantIdAndSystemKey(DEFAULT_TENANT, INTEGRATION_SYSTEM_KEY)
                .orElse(null);

        if (cfg != null && cfg.getSecrets() != null
                && cfg.getSecrets().get(SECRET_FIELD_OKTA_CLIENT_SECRET) != null) {
            try {
                String encrypted = String.valueOf(cfg.getSecrets().get(SECRET_FIELD_OKTA_CLIENT_SECRET));
                out.setOktaClientSecret(integrationConfigService.decrypt(encrypted));
            } catch (Exception e) {
                log.warn("Failed to decrypt AI Gateway Okta client_secret: {}", e.getMessage());
                // Return null secret — ecm-ocr will treat this as "not configured" and stay on direct route
            }
        }

        // Never log the secret value itself, just that we served it
        log.debug("AI Gateway OCR credentials served: route={}, baseUrl={}, clientIdConfigured={}, secretConfigured={}",
                out.getRoute(),
                out.getBaseUrl() != null,
                out.getOktaClientId() != null,
                out.getOktaClientSecret() != null);

        return ResponseEntity.ok(ApiResponse.ok(out));
    }
}
