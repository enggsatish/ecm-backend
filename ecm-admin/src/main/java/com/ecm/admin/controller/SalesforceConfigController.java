package com.ecm.admin.controller;

import com.ecm.admin.dto.IntegrationConfigDto.*;
import com.ecm.admin.service.IntegrationConfigService;
import com.ecm.admin.service.SalesforceClient;
import com.ecm.common.audit.AuditLog;
import com.ecm.common.model.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Salesforce Integration Configuration endpoints.
 *
 * GET  /api/admin/integrations/salesforce        → config fields + masked secret + test status
 * PUT  /api/admin/integrations/salesforce        → save config + encrypt secret
 * POST /api/admin/integrations/salesforce/test   → OAuth client-credentials auth test
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/integrations/salesforce")
@RequiredArgsConstructor
@PreAuthorize("hasPermission(null, 'admin:configure')")
public class SalesforceConfigController {

    private static final String TENANT = "default";

    private final IntegrationConfigService integrationService;
    private final SalesforceClient salesforceClient;

    @GetMapping
    public ResponseEntity<ApiResponse<SalesforceConfigResponse>> getConfig() {
        return ResponseEntity.ok(ApiResponse.ok(integrationService.getSalesforce(TENANT)));
    }

    @PutMapping
    @AuditLog(event = "INTEGRATION_CONFIG_UPDATED", resourceType = "SALESFORCE_CONFIG")
    public ResponseEntity<ApiResponse<SalesforceConfigResponse>> saveConfig(
            @RequestBody SalesforceConfigRequest req) {
        SalesforceConfigResponse saved = integrationService.saveSalesforce(TENANT, req);
        return ResponseEntity.ok(ApiResponse.ok(saved, "Salesforce configuration saved"));
    }

    @PostMapping("/test")
    @AuditLog(event = "INTEGRATION_TEST", resourceType = "SALESFORCE_CONFIG")
    public ResponseEntity<ApiResponse<TestConnectionResponse>> testConnection() {
        if (!integrationService.isSalesforceEnabled(TENANT)) {
            return ResponseEntity.ok(ApiResponse.ok(
                    new TestConnectionResponse(false, "Salesforce integration is disabled")));
        }
        try {
            salesforceClient.testAuthenticate();
            integrationService.recordSalesforceTestResult(TENANT, true, "OK");
            return ResponseEntity.ok(ApiResponse.ok(
                    new TestConnectionResponse(true, "Connection successful — authenticated via client credentials")));
        } catch (Exception e) {
            log.error("[Salesforce] Connection test failed: {}", e.getMessage());
            integrationService.recordSalesforceTestResult(TENANT, false, e.getMessage());
            return ResponseEntity.ok(ApiResponse.ok(
                    new TestConnectionResponse(false, "Test failed: " + e.getMessage())));
        }
    }
}
