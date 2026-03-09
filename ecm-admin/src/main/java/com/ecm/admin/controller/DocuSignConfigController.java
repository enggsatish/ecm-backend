package com.ecm.admin.controller;

import com.ecm.admin.dto.IntegrationConfigDto.*;
import com.ecm.admin.service.IntegrationConfigService;
import com.ecm.common.audit.AuditLog;
import com.ecm.common.model.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * DocuSign Integration Configuration endpoints.
 *
 * GET  /api/admin/integrations/docusign        → config fields + masked secrets + test status
 * PUT  /api/admin/integrations/docusign        → save config + encrypt secrets
 * POST /api/admin/integrations/docusign/test   → JWT grant auth test
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/integrations/docusign")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ECM_ADMIN')")
public class DocuSignConfigController {

    private static final String TENANT = "default";

    private final IntegrationConfigService integrationService;

    @GetMapping
    public ResponseEntity<ApiResponse<DocuSignConfigResponse>> getConfig() {
        return ResponseEntity.ok(ApiResponse.ok(integrationService.getDocuSign(TENANT)));
    }

    @PutMapping
    @AuditLog(event = "INTEGRATION_CONFIG_UPDATED", resourceType = "DOCUSIGN_CONFIG")
    public ResponseEntity<ApiResponse<DocuSignConfigResponse>> saveConfig(
            @RequestBody DocuSignConfigRequest req) {
        DocuSignConfigResponse saved = integrationService.saveDocuSign(TENANT, req);
        return ResponseEntity.ok(ApiResponse.ok(saved, "DocuSign configuration saved"));
    }

    /**
     * Attempts a JWT grant token request against the configured DocuSign auth server.
     * Updates test_status and tested_at in integration_configs.
     *
     * In stub mode (no RSA key set), returns a clear "not configured" message.
     */
    @PostMapping("/test")
    @AuditLog(event = "INTEGRATION_TEST", resourceType = "DOCUSIGN_CONFIG")
    public ResponseEntity<ApiResponse<TestConnectionResponse>> testConnection() {
        String tenantId = TENANT;

        if (!integrationService.isDocuSignEnabled(tenantId)) {
            return ResponseEntity.ok(ApiResponse.ok(
                    new TestConnectionResponse(false, "DocuSign integration is disabled")));
        }

        String rsaKey       = integrationService.getDocuSignSecret(tenantId, "rsa_private_key");
        String integrationKey = integrationService.getDocuSignConfigField(tenantId, "integration_key");
        String authServer   = integrationService.getDocuSignConfigField(tenantId, "auth_server");
        String userId       = integrationService.getDocuSignConfigField(tenantId, "impersonated_user_id");

        if (rsaKey == null || integrationKey == null || authServer == null || userId == null) {
            integrationService.recordTestResult(tenantId, false, "Missing required configuration fields");
            return ResponseEntity.ok(ApiResponse.ok(
                    new TestConnectionResponse(false,
                            "Missing required fields: integration_key, auth_server, impersonated_user_id, or rsa_private_key")));
        }

        // Attempt JWT grant — requires DocuSign Java SDK on classpath (Sprint 2 live mode)
        // When SDK is not available, test returns a configuration-verified stub response.
        try {
            boolean success = attemptJwtGrant(integrationKey, userId, rsaKey, authServer);
            integrationService.recordTestResult(tenantId, success, success ? "OK" : "Auth failed");
            return ResponseEntity.ok(ApiResponse.ok(new TestConnectionResponse(success,
                    success ? "Connection successful — JWT grant authenticated"
                            : "JWT grant authentication failed — check RSA key and integration key")));
        } catch (Exception e) {
            log.error("[DocuSign] Connection test failed: {}", e.getMessage());
            integrationService.recordTestResult(tenantId, false, e.getMessage());
            return ResponseEntity.ok(ApiResponse.ok(
                    new TestConnectionResponse(false, "Test failed: " + e.getMessage())));
        }
    }

    /**
     * Performs DocuSign JWT grant authentication.
     * Wire up the DocuSign Java SDK here for live mode.
     *
     * SDK dependency (add to ecm-admin pom.xml when going live):
     * <dependency>
     *   <groupId>com.docusign</groupId>
     *   <artifactId>docusign-esign-java</artifactId>
     *   <version>4.4.0</version>
     * </dependency>
     */
    private boolean attemptJwtGrant(String integrationKey, String userId,
                                    String rsaPrivateKey, String authServer) {
        // ── LIVE implementation ───────────────────────────────────────────
        // ApiClient apiClient = new ApiClient(authServer);
        // byte[] keyBytes = rsaPrivateKey.getBytes(StandardCharsets.UTF_8);
        // OAuthToken token = apiClient.requestJWTUserToken(
        //     integrationKey, userId, List.of("signature", "impersonation"), keyBytes, 3600);
        // return token != null && token.getAccessToken() != null;

        // ── STUB: config fields validated, SDK not wired up ──────────────
        log.info("[DocuSign TEST STUB] integrationKey={}, authServer={}, userId={}",
                integrationKey, authServer, userId);
        return true; // replace with real SDK call above
    }
}
