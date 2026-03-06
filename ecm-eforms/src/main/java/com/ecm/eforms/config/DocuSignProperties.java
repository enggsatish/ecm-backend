package com.ecm.eforms.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * DocuSign integration properties bound from application.yml.
 *
 * Prefix: ecm.docusign
 *
 * ecm.docusign.enabled controls whether live API calls are made.
 * When false (default for Sprint 1), DocuSignService operates in stub mode
 * and logs all operations without contacting DocuSign servers.
 *
 * To enable live mode:
 *   1. Set ecm.docusign.enabled=true
 *   2. Provide all required credentials (integration-key through private-key-path)
 *   3. Ensure the RSA private key PEM file exists at private-key-path
 *   4. Configure DocuSign Connect in your DocuSign account to POST to:
 *        https://your-domain/api/eforms/docusign/webhook
 *      with HMAC enabled using webhook-secret
 */
@Configuration
@ConfigurationProperties(prefix = "ecm.docusign")
@Data
public class DocuSignProperties {

    /** Master switch. false = stub mode (Sprint 1). true = live DocuSign calls */
    private boolean enabled = false;

    /** DocuSign OAuth integration key (client ID) */
    private String integrationKey;

    /** DocuSign account ID (UUID from DocuSign Admin) */
    private String accountId;

    /** DocuSign user ID for JWT Grant impersonation */
    private String userId;

    /** Absolute path to the RSA private key PEM file for JWT Grant auth */
    private String privateKeyPath;

    /**
     * DocuSign REST API base URL.
     * Sandbox:    https://demo.docusign.net/restapi
     * Production: https://www.docusign.net/restapi
     */
    private String baseUrl = "https://demo.docusign.net/restapi";

    /**
     * DocuSign OAuth auth server.
     * Sandbox:    https://account-d.docusign.com
     * Production: https://account.docusign.com
     */
    private String authServer = "https://account-d.docusign.com";

    /**
     * HMAC secret configured in DocuSign Connect.
     * Used to verify the X-DocuSign-Signature-1 header on webhook events.
     * Must match exactly what is set in DocuSign Connect configuration.
     */
    private String webhookSecret;

    /** Days before the DocuSign envelope expires (default: 30) */
    private int expiryDays = 30;
}