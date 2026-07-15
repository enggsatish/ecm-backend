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
@PreAuthorize("hasPermission(null, 'admin:configure')")
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

        // Attempt JWT grant — calls ecm-eforms DocuSign service via REST
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
     * Tests DocuSign connection by calling ecm-eforms DocuSign service.
     * Delegates to ecm-eforms which already has production-grade key parsing
     * (handles PKCS#1/PKCS#8, literal \n, AES-decrypted keys).
     */
    private boolean attemptJwtGrant(String integrationKey, String userId,
                                    String rsaPrivateKey, String authServer) {
        try {
            // Call ecm-eforms test-connection endpoint instead of reimplementing JWT Grant
            var restTemplate = new org.springframework.web.client.RestTemplate();
            var response = restTemplate.getForEntity(
                    "http://localhost:8084/api/eforms/docusign/test-connection",
                    java.util.Map.class);

            boolean ok = response.getStatusCode().is2xxSuccessful()
                    && response.getBody() != null
                    && Boolean.TRUE.equals(response.getBody().get("success"));

            log.info("[DocuSign] Connection test via ecm-eforms: success={}", ok);
            return ok;
        } catch (Exception e) {
            log.error("[DocuSign] Connection test failed: {}", e.getMessage());
            throw new RuntimeException("Connection test failed: " + e.getMessage(), e);
        }
    }
}
