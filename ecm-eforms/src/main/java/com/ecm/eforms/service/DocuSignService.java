package com.ecm.eforms.service;

import com.ecm.eforms.model.entity.FormSubmission;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPrivateCrtKeySpec;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DocuSign Integration Service — SDK-Free REST API Implementation.
 *
 * Uses direct HTTP calls via Spring RestTemplate instead of the DocuSign Java SDK.
 * This eliminates all SDK transitive dependencies (Jersey, Oltu OAuth2, etc.)
 * while providing the same functionality with full control.
 *
 * Reads config from ecm_admin.integration_configs (set via Admin UI).
 * When disabled or config incomplete, falls back to stub mode.
 *
 * Authentication: JWT Grant (RS256) → access token → Bearer header.
 * API version: DocuSign eSignature REST API v2.1
 */
@Service
@Slf4j
public class DocuSignService {

    private final JdbcTemplate jdbcTemplate;
    private final PdfGenerationService pdfGenerator;
    private final ObjectMapper objectMapper;

    /** Same master key used by ecm-admin IntegrationConfigService for AES-GCM encryption */
    @Value("${ecm.master-encrypt-key:#{null}}")
    private String masterKeyBase64;

    private SecretKey masterKey;

    private static final String AES_ALGO     = "AES";
    private static final String AES_GCM_ALGO = "AES/GCM/NoPadding";
    private static final int    GCM_TAG_BITS = 128;

    /** Cached access tokens keyed by integrationKey to avoid re-auth on every call */
    private final ConcurrentHashMap<String, CachedToken> tokenCache = new ConcurrentHashMap<>();

    private static final RestTemplate restTemplate = new RestTemplate();

    public DocuSignService(JdbcTemplate jdbcTemplate, PdfGenerationService pdfGenerator,
                           ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.pdfGenerator = pdfGenerator;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void initMasterKey() {
        if (masterKeyBase64 != null && !masterKeyBase64.isBlank()) {
            byte[] keyBytes = Base64.getDecoder().decode(masterKeyBase64);
            masterKey = new SecretKeySpec(keyBytes, AES_ALGO);
            log.info("[DocuSign] Using configured master encryption key");
            return;
        }
        try {
            byte[] keyBytes = Base64.getDecoder().decode(com.ecm.common.util.EncryptionUtil.resolveMasterKeyBase64());
            masterKey = new SecretKeySpec(keyBytes, AES_ALGO);
            log.warn("[DocuSign] ecm.master-encrypt-key not set — using a key persisted at {} (shared with " +
                    "ecm-admin) so secrets survive restarts. Set ecm.master-encrypt-key explicitly for any " +
                    "shared/production deployment.", com.ecm.common.util.EncryptionUtil.devKeyFilePath());
        } catch (Exception e) {
            log.error("[DocuSign] Failed to resolve a persisted dev key ({}) — secrets decryption will fail " +
                    "until ecm.master-encrypt-key is set.", e.getMessage());
        }
    }

    // ── Config helpers ────────────────────────────────────────────────────────

    private boolean isEnabled() {
        try {
            Boolean enabled = jdbcTemplate.queryForObject(
                    "SELECT enabled FROM ecm_admin.integration_configs " +
                    "WHERE tenant_id = 'default' AND system_key = 'DOCUSIGN'",
                    Boolean.class);
            return Boolean.TRUE.equals(enabled);
        } catch (Exception e) {
            return false;
        }
    }

    private String getConfigField(String field) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT config->>'" + field + "' FROM ecm_admin.integration_configs " +
                    "WHERE tenant_id = 'default' AND system_key = 'DOCUSIGN'",
                    String.class);
        } catch (Exception e) {
            return null;
        }
    }

    private String getSecretField(String field) {
        try {
            String encrypted = jdbcTemplate.queryForObject(
                    "SELECT secrets->>'" + field + "' FROM ecm_admin.integration_configs " +
                    "WHERE tenant_id = 'default' AND system_key = 'DOCUSIGN'",
                    String.class);
            if (encrypted == null || encrypted.isBlank()) return null;

            // Secrets are AES-GCM encrypted by ecm-admin IntegrationConfigService
            // Format: "base64(iv):base64(ciphertext)"
            return decryptSecret(encrypted);
        } catch (Exception e) {
            log.warn("[DocuSign] Could not read secret field {}: {}", field, e.getMessage());
            return null;
        }
    }

    /**
     * Decrypts a value encrypted by ecm-admin's IntegrationConfigService.
     * Format: "base64(iv):base64(ciphertext)" using AES-GCM-256.
     */
    private String decryptSecret(String encryptedValue) {
        if (masterKey == null) {
            throw new IllegalStateException(
                    "Cannot decrypt secrets — ecm.master-encrypt-key not configured in ecm-eforms");
        }
        try {
            String[] parts = encryptedValue.split(":", 2);
            if (parts.length != 2) {
                // Not encrypted — return as-is (dev/plaintext mode)
                return encryptedValue;
            }
            byte[] iv = Base64.getDecoder().decode(parts[0]);
            byte[] ciphertext = Base64.getDecoder().decode(parts[1]);

            Cipher cipher = Cipher.getInstance(AES_GCM_ALGO);
            cipher.init(Cipher.DECRYPT_MODE, masterKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("AES-GCM decryption failed for secret — " +
                    "ensure ecm.master-encrypt-key matches the key used by ecm-admin", e);
        }
    }

    // ── JWT Grant Authentication ──────────────────────────────────────────────

    /**
     * Obtains a valid access token via JWT Grant (RS256).
     * Tokens are cached and reused until 5 minutes before expiry.
     *
     * JWT Grant flow:
     *   1. Build a JWT assertion: iss=integrationKey, sub=userId, aud=authServer, scope=signature impersonation
     *   2. Sign with RSA private key (RS256)
     *   3. POST to {authServer}/oauth/token with grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer
     *   4. Receive access_token (valid for ~1 hour)
     */
    private String getAccessToken() throws Exception {
        String integrationKey     = getConfigField("integration_key");
        String impersonatedUserId = getConfigField("impersonated_user_id");
        String authServer         = getConfigField("auth_server");
        String rsaPrivateKeyPem   = getSecretField("rsa_private_key");

        if (integrationKey == null || impersonatedUserId == null
                || authServer == null || rsaPrivateKeyPem == null) {
            throw new IllegalStateException(
                    "DocuSign not fully configured — check Admin → Integrations → DocuSign");
        }

        // Normalize auth server URL
        if (!authServer.startsWith("https://")) authServer = "https://" + authServer;
        if (authServer.endsWith("/")) authServer = authServer.substring(0, authServer.length() - 1);

        // Check cache
        CachedToken cached = tokenCache.get(integrationKey);
        if (cached != null && cached.isValid()) {
            return cached.accessToken;
        }

        // Build JWT assertion
        long now = Instant.now().getEpochSecond();
        long exp = now + 3600; // 1 hour

        // Extract just the host for the "aud" claim (e.g., "account-d.docusign.com")
        String audHost = authServer.replace("https://", "").replace("http://", "");

        String headerJson = "{\"typ\":\"JWT\",\"alg\":\"RS256\"}";
        String payloadJson = objectMapper.writeValueAsString(Map.of(
                "iss", integrationKey,
                "sub", impersonatedUserId,
                "aud", audHost,
                "iat", now,
                "exp", exp,
                "scope", "signature impersonation"
        ));

        String headerB64  = base64UrlEncode(headerJson.getBytes(StandardCharsets.UTF_8));
        String payloadB64 = base64UrlEncode(payloadJson.getBytes(StandardCharsets.UTF_8));
        String signingInput = headerB64 + "." + payloadB64;

        // Sign with RSA private key
        PrivateKey privateKey = loadPrivateKey(rsaPrivateKeyPem);
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(privateKey);
        sig.update(signingInput.getBytes(StandardCharsets.UTF_8));
        String signatureB64 = base64UrlEncode(sig.sign());

        String jwtAssertion = signingInput + "." + signatureB64;

        // Exchange JWT for access token
        String tokenUrl = authServer + "/oauth/token";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        String body = "grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=" + jwtAssertion;
        HttpEntity<String> request = new HttpEntity<>(body, headers);

        ResponseEntity<JsonNode> response = restTemplate.exchange(
                tokenUrl, HttpMethod.POST, request, JsonNode.class);

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new RuntimeException("DocuSign JWT Grant failed: HTTP " + response.getStatusCode());
        }

        String accessToken = response.getBody().get("access_token").asText();
        long expiresIn = response.getBody().has("expires_in")
                ? response.getBody().get("expires_in").asLong() : 3600;

        // Cache the token (expire 5 minutes early for safety)
        tokenCache.put(integrationKey, new CachedToken(accessToken, now + expiresIn - 300));

        log.info("[DocuSign] JWT Grant authenticated — token expires in {}s", expiresIn);
        return accessToken;
    }

    /**
     * Test connection — usable from admin controller for "Test Connection" button.
     * Returns true if JWT grant succeeds.
     */
    public boolean testConnection() {
        try {
            getAccessToken();
            return true;
        } catch (Exception e) {
            log.error("[DocuSign] Connection test failed: {}", e.getMessage());
            return false;
        }
    }

    private String getBaseUrl() {
        String baseUrl = getConfigField("base_url");
        if (baseUrl == null || baseUrl.isBlank()) baseUrl = "https://demo.docusign.net/restapi";
        if (!baseUrl.startsWith("https://")) baseUrl = "https://" + baseUrl;
        if (!baseUrl.contains("/restapi")) baseUrl = baseUrl + "/restapi";
        return baseUrl;
    }

    private String getAccountId() {
        return getConfigField("account_id");
    }

    // ── Email Branding ────────────────────────────────────────────────────────

    /**
     * Resolves the email subject using the configured template.
     * Supports tokens: {companyName}, {documentName}, {signerName}, {signerEmail}
     */
    private String resolveEmailSubject(String documentName, String signerName, String signerEmail, String overrideSubject) {
        if (overrideSubject != null && !overrideSubject.isBlank()) {
            return substituteTokens(overrideSubject, documentName, signerName, signerEmail);
        }
        String template = getConfigField("email_subject_template");
        if (template == null || template.isBlank()) {
            String company = getConfigField("company_name");
            return (company != null ? company : "ECM") + " — Please sign: " + documentName;
        }
        return substituteTokens(template, documentName, signerName, signerEmail);
    }

    /**
     * Resolves the email body using the configured template.
     */
    private String resolveEmailBody(String documentName, String signerName, String signerEmail, String overrideBody) {
        if (overrideBody != null && !overrideBody.isBlank()) {
            return substituteTokens(overrideBody, documentName, signerName, signerEmail);
        }
        String template = getConfigField("email_body_template");
        if (template == null || template.isBlank()) {
            return "Please review and sign the attached document.";
        }
        return substituteTokens(template, documentName, signerName, signerEmail);
    }

    private String substituteTokens(String template, String documentName, String signerName, String signerEmail) {
        String company = getConfigField("company_name");
        return template
                .replace("{companyName}", company != null ? company : "ECM")
                .replace("{documentName}", documentName != null ? documentName : "Document")
                .replace("{signerName}", signerName != null ? signerName : "")
                .replace("{signerEmail}", signerEmail != null ? signerEmail : "");
    }

    private HttpHeaders authHeaders() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(getAccessToken());
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        return headers;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Signer identity + email overrides supplied at submission time (the
     * signing step in the form-fill flow), as opposed to the form's static
     * design-time config. All fields optional — null/blank falls back to
     * the submitter's own identity and the admin's default email template.
     */
    public record SigningRequest(String signerEmail, String signerName,
                                  String emailSubjectOverride, String emailBodyOverride) {}

    /**
     * Creates a DocuSign signing envelope for the given form submission.
     *
     * @return the DocuSign envelopeId
     */
    public String createEnvelope(FormSubmission submission) {
        return createEnvelope(submission, null);
    }

    /**
     * Creates a DocuSign signing envelope for the given form submission,
     * using signer info captured at fill time.
     *
     * @return the DocuSign envelopeId
     */
    public String createEnvelope(FormSubmission submission, SigningRequest signing) {
        if (!isEnabled()) {
            String stubId = "STUB-ENVELOPE-" + UUID.randomUUID();
            log.info("[DocuSign STUB] createEnvelope: submissionId={}, stubEnvelopeId={}",
                    submission.getId(), stubId);
            return stubId;
        }

        try {
            byte[] pdfBytes = pdfGenerator.generate(submission);
            ObjectNode envelopeJson = buildEnvelopeJson(submission, pdfBytes, signing);

            String url = getBaseUrl() + "/v2.1/accounts/" + getAccountId() + "/envelopes";
            HttpEntity<String> request = new HttpEntity<>(
                    objectMapper.writeValueAsString(envelopeJson), authHeaders());

            ResponseEntity<JsonNode> response = restTemplate.exchange(
                    url, HttpMethod.POST, request, JsonNode.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new RuntimeException("Create envelope failed: HTTP " + response.getStatusCode());
            }

            String envelopeId = response.getBody().get("envelopeId").asText();
            String status = response.getBody().has("status")
                    ? response.getBody().get("status").asText() : "unknown";

            log.info("[DocuSign] Envelope created: envelopeId={}, submissionId={}, status={}",
                    envelopeId, submission.getId(), status);
            return envelopeId;

        } catch (Exception e) {
            log.error("[DocuSign] createEnvelope failed for submissionId={}: {}",
                    submission.getId(), e.getMessage(), e);
            throw new RuntimeException("DocuSign envelope creation failed: " + e.getMessage(), e);
        }
    }

    /**
     * Creates an envelope for an arbitrary document (for case manager / workflow use).
     * Supports flexible signature placement: auto-detect anchors, last page, or specific coordinates.
     *
     * @param placement  "auto" = use /sig1/ anchor, "lastPage" = bottom of last page, "specific" = custom page/x/y
     */
    public String createEnvelopeForDocument(byte[] pdfBytes, String documentName,
                                             String signerEmail, String signerName,
                                             String emailSubject,
                                             String placement, String page, String xPos, String yPos,
                                             boolean requireInitials, boolean requireDateSigned) {
        if (!isEnabled()) {
            String stubId = "STUB-ENVELOPE-" + UUID.randomUUID();
            log.info("[DocuSign STUB] createEnvelopeForDocument: doc={}, signer={}, stubId={}",
                    documentName, signerEmail, stubId);
            return stubId;
        }

        try {
            ObjectNode envelope = objectMapper.createObjectNode();
            envelope.put("emailSubject", resolveEmailSubject(documentName, signerName, signerEmail, emailSubject));
            envelope.put("emailBlurb", resolveEmailBody(documentName, signerName, signerEmail, null));
            envelope.put("status", "sent");

            // Document
            ObjectNode doc = objectMapper.createObjectNode();
            doc.put("documentBase64", Base64.getEncoder().encodeToString(pdfBytes));
            doc.put("name", documentName);
            doc.put("fileExtension", "pdf");
            doc.put("documentId", "1");
            envelope.set("documents", objectMapper.createArrayNode().add(doc));

            // Build signature tabs based on placement option
            var signHereTabs = objectMapper.createArrayNode();

            if ("auto".equals(placement)) {
                // Auto-detect: use anchor string /sig1/ embedded in the PDF
                ObjectNode signHere = objectMapper.createObjectNode();
                signHere.put("anchorString", "/sig1/");
                signHere.put("anchorUnits", "pixels");
                signHere.put("anchorXOffset", "20");
                signHere.put("anchorYOffset", "10");
                signHereTabs.add(signHere);
            } else if ("specific".equals(placement)) {
                // Specific page and coordinates
                ObjectNode signHere = objectMapper.createObjectNode();
                signHere.put("documentId", "1");
                signHere.put("pageNumber", page != null ? page : "1");
                signHere.put("xPosition", xPos != null ? xPos : "100");
                signHere.put("yPosition", yPos != null ? yPos : "700");
                signHereTabs.add(signHere);
            } else {
                // Default / "lastPage": bottom of last page
                // DocuSign uses pageNumber "0" or "-1" for last page — we'll use a large number
                // as fallback; DocuSign clamps to the actual last page
                ObjectNode signHere = objectMapper.createObjectNode();
                signHere.put("documentId", "1");
                signHere.put("pageNumber", "999");  // DocuSign clamps to last page
                signHere.put("xPosition", "100");
                signHere.put("yPosition", "650");
                signHereTabs.add(signHere);
            }

            ObjectNode tabs = objectMapper.createObjectNode();
            tabs.set("signHereTabs", signHereTabs);

            // Optional: initials tab
            if (requireInitials) {
                ObjectNode initHere = objectMapper.createObjectNode();
                if ("auto".equals(placement)) {
                    initHere.put("anchorString", "/init1/");
                    initHere.put("anchorUnits", "pixels");
                    initHere.put("anchorXOffset", "20");
                    initHere.put("anchorYOffset", "10");
                } else {
                    initHere.put("documentId", "1");
                    initHere.put("pageNumber", "999");
                    initHere.put("xPosition", "400");
                    initHere.put("yPosition", "650");
                }
                tabs.set("initialHereTabs", objectMapper.createArrayNode().add(initHere));
            }

            // Optional: date signed tab
            if (requireDateSigned) {
                ObjectNode dateSigned = objectMapper.createObjectNode();
                if ("auto".equals(placement)) {
                    dateSigned.put("anchorString", "/date1/");
                    dateSigned.put("anchorUnits", "pixels");
                    dateSigned.put("anchorXOffset", "20");
                    dateSigned.put("anchorYOffset", "10");
                } else {
                    dateSigned.put("documentId", "1");
                    dateSigned.put("pageNumber", "999");
                    dateSigned.put("xPosition", "100");
                    dateSigned.put("yPosition", "720");
                }
                tabs.set("dateSignedTabs", objectMapper.createArrayNode().add(dateSigned));
            }

            ObjectNode signer = objectMapper.createObjectNode();
            signer.put("email", signerEmail);
            signer.put("name", signerName);
            signer.put("recipientId", "1");
            signer.put("routingOrder", "1");
            signer.set("tabs", tabs);

            ObjectNode recipients = objectMapper.createObjectNode();
            recipients.set("signers", objectMapper.createArrayNode().add(signer));
            envelope.set("recipients", recipients);

            String url = getBaseUrl() + "/v2.1/accounts/" + getAccountId() + "/envelopes";
            HttpEntity<String> request = new HttpEntity<>(
                    objectMapper.writeValueAsString(envelope), authHeaders());

            ResponseEntity<JsonNode> response = restTemplate.exchange(
                    url, HttpMethod.POST, request, JsonNode.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new RuntimeException("Create envelope failed: HTTP " + response.getStatusCode());
            }

            String envelopeId = response.getBody().get("envelopeId").asText();
            log.info("[DocuSign] Document envelope created: envelopeId={}, doc={}, signer={}, placement={}",
                    envelopeId, documentName, signerEmail, placement);
            return envelopeId;

        } catch (Exception e) {
            log.error("[DocuSign] createEnvelopeForDocument failed: {}", e.getMessage(), e);
            throw new RuntimeException("DocuSign envelope creation failed: " + e.getMessage(), e);
        }
    }

    /**
     * Downloads the completed signed document from DocuSign.
     *
     * @return raw PDF bytes
     */
    public byte[] downloadSignedDocument(String envelopeId) {
        if (!isEnabled() || envelopeId.startsWith("STUB-")) {
            log.info("[DocuSign STUB] downloadSignedDocument: envelopeId={}", envelopeId);
            return new byte[0];
        }

        try {
            String url = getBaseUrl() + "/v2.1/accounts/" + getAccountId()
                    + "/envelopes/" + envelopeId + "/documents/combined";

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(getAccessToken());
            headers.setAccept(List.of(MediaType.APPLICATION_PDF, MediaType.APPLICATION_OCTET_STREAM));

            HttpEntity<Void> request = new HttpEntity<>(headers);
            ResponseEntity<byte[]> response = restTemplate.exchange(
                    url, HttpMethod.GET, request, byte[].class);

            byte[] pdfBytes = response.getBody();
            log.info("[DocuSign] Downloaded signed document: envelopeId={}, size={} bytes",
                    envelopeId, pdfBytes != null ? pdfBytes.length : 0);
            return pdfBytes != null ? pdfBytes : new byte[0];

        } catch (Exception e) {
            log.error("[DocuSign] downloadSignedDocument failed for envelopeId={}: {}",
                    envelopeId, e.getMessage(), e);
            return new byte[0];
        }
    }

    /**
     * Validates the HMAC signature on a DocuSign Connect webhook event.
     */
    public void validateWebhookHmac(byte[] rawBody, String hmacHeader) {
        // Check if HMAC validation is configured FIRST
        String secret = getSecretField("webhook_hmac_secret");
        if (secret == null || secret.isBlank()) {
            log.warn("[DocuSign Webhook] HMAC secret not configured — skipping validation");
            return;
        }

        // HMAC is configured — header is now required
        if (hmacHeader == null || hmacHeader.isBlank()) {
            throw new SecurityException("Missing X-DocuSign-Signature-1 header");
        }

        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expectedBytes = mac.doFinal(rawBody);
            String expected = Base64.getEncoder().encodeToString(expectedBytes);

            if (!constantTimeEquals(expected, hmacHeader)) {
                throw new SecurityException("DocuSign HMAC signature validation failed");
            }
        } catch (SecurityException se) {
            throw se;
        } catch (Exception e) {
            throw new SecurityException("HMAC validation error: " + e.getMessage(), e);
        }
    }

    /**
     * Voids (cancels) an in-progress envelope.
     */
    public void voidEnvelope(String envelopeId, String reason) {
        if (!isEnabled() || envelopeId.startsWith("STUB-")) {
            log.info("[DocuSign STUB] voidEnvelope: {}", envelopeId);
            return;
        }
        try {
            String url = getBaseUrl() + "/v2.1/accounts/" + getAccountId()
                    + "/envelopes/" + envelopeId;

            ObjectNode body = objectMapper.createObjectNode();
            body.put("status", "voided");
            body.put("voidedReason", reason);

            HttpEntity<String> request = new HttpEntity<>(
                    objectMapper.writeValueAsString(body), authHeaders());
            restTemplate.exchange(url, HttpMethod.PUT, request, JsonNode.class);

            log.info("[DocuSign] Envelope voided: envelopeId={}", envelopeId);
        } catch (Exception e) {
            log.error("[DocuSign] voidEnvelope failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Resends the signing email to recipients.
     */
    public void resendEnvelope(String envelopeId) {
        if (!isEnabled() || envelopeId.startsWith("STUB-")) {
            log.info("[DocuSign STUB] resendEnvelope: {}", envelopeId);
            return;
        }
        try {
            String url = getBaseUrl() + "/v2.1/accounts/" + getAccountId()
                    + "/envelopes/" + envelopeId + "?resend_envelope=true";

            ObjectNode body = objectMapper.createObjectNode();
            HttpEntity<String> request = new HttpEntity<>(
                    objectMapper.writeValueAsString(body), authHeaders());
            restTemplate.exchange(url, HttpMethod.PUT, request, JsonNode.class);

            log.info("[DocuSign] Envelope resent: envelopeId={}", envelopeId);
        } catch (Exception e) {
            log.error("[DocuSign] resendEnvelope failed: {}", e.getMessage(), e);
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /**
     * Builds the envelope JSON by scanning the form schema for eSign fields:
     *   - SIGNER_EMAIL → recipient email address
     *   - SIGNATURE    → signHere tab (anchor-based placement)
     *   - INITIALS     → initialHere tab (anchor-based placement)
     */
    private ObjectNode buildEnvelopeJson(FormSubmission submission, byte[] pdfBytes, SigningRequest signing) {
        ObjectNode envelope = objectMapper.createObjectNode();
        String formName = submission.getFormKey() != null ? submission.getFormKey() : "Form Submission";

        // Signer identity: fill-time signing step (SigningRequest) takes priority
        // over the submitter's own identity — a form is often filled by someone
        // other than the person who needs to sign it.
        String signerEmail = signing != null && signing.signerEmail() != null && !signing.signerEmail().isBlank()
                ? signing.signerEmail() : submission.getSubmittedBy();
        String signerName = signing != null && signing.signerName() != null && !signing.signerName().isBlank()
                ? signing.signerName() : submission.getSubmittedByName();

        String subjectOverride = signing != null ? signing.emailSubjectOverride() : null;
        String bodyOverride    = signing != null ? signing.emailBodyOverride()    : null;
        envelope.put("emailSubject", resolveEmailSubject(formName, signerName, signerEmail, subjectOverride));
        envelope.put("emailBlurb", resolveEmailBody(formName, signerName, signerEmail, bodyOverride));
        envelope.put("status", "sent");

        // Document
        ObjectNode doc = objectMapper.createObjectNode();
        doc.put("documentBase64", Base64.getEncoder().encodeToString(pdfBytes));
        doc.put("name", formName);
        doc.put("fileExtension", "pdf");
        doc.put("documentId", "1");
        envelope.set("documents", objectMapper.createArrayNode().add(doc));

        // Scan schema for eSign fields — may override signerEmail from form data
        var schema = submission.getFormSchemaSnapshot();
        var data = submission.getSubmissionData();
        ArrayNode signHereTabs = objectMapper.createArrayNode();
        ArrayNode initialHereTabs = objectMapper.createArrayNode();

        if (schema != null && schema.getSections() != null) {
            for (var section : schema.getSections()) {
                if (section.getFields() == null) continue;
                for (var field : section.getFields()) {
                    if (field.getType() == com.ecm.eforms.model.schema.FieldType.SIGNER_EMAIL
                            && data != null) {
                        Object val = data.get(field.getKey());
                        if (val != null && !val.toString().isBlank()) {
                            signerEmail = val.toString().trim();
                        }
                    }
                    if (field.getType() == com.ecm.eforms.model.schema.FieldType.SIGNATURE) {
                        String anchor = "/sig" + (field.getId() != null
                                ? field.getId().hashCode() & 0xFFF : "1") + "/";
                        ObjectNode sh = objectMapper.createObjectNode();
                        sh.put("anchorString", anchor);
                        sh.put("anchorUnits", "pixels");
                        sh.put("anchorXOffset", "0");
                        sh.put("anchorYOffset", "-10");
                        signHereTabs.add(sh);
                    }
                    if (field.getType() == com.ecm.eforms.model.schema.FieldType.INITIALS) {
                        String anchor = "/init" + (field.getId() != null
                                ? field.getId().hashCode() & 0xFFF : "1") + "/";
                        ObjectNode ih = objectMapper.createObjectNode();
                        ih.put("anchorString", anchor);
                        ih.put("anchorUnits", "pixels");
                        ih.put("anchorXOffset", "0");
                        ih.put("anchorYOffset", "-10");
                        initialHereTabs.add(ih);
                    }
                }
            }
        }

        // Fallback: signerName already set from line 627; signerEmail may have been
        // overridden by a SIGNER_EMAIL form field above
        if (signerName == null || signerName.isBlank()) {
            signerName = signerEmail;
        }

        // Fallback signature tab if none in schema
        if (signHereTabs.isEmpty()) {
            ObjectNode fallback = objectMapper.createObjectNode();
            fallback.put("documentId", "1");
            fallback.put("pageNumber", "1");
            fallback.put("xPosition", "100");
            fallback.put("yPosition", "700");
            signHereTabs.add(fallback);
        }

        ObjectNode tabs = objectMapper.createObjectNode();
        tabs.set("signHereTabs", signHereTabs);
        if (!initialHereTabs.isEmpty()) tabs.set("initialHereTabs", initialHereTabs);

        ObjectNode signer = objectMapper.createObjectNode();
        signer.put("email", signerEmail);
        signer.put("name", signerName);
        signer.put("recipientId", "1");
        signer.put("routingOrder", "1");
        signer.set("tabs", tabs);

        ObjectNode recipients = objectMapper.createObjectNode();
        recipients.set("signers", objectMapper.createArrayNode().add(signer));
        envelope.set("recipients", recipients);

        log.info("[DocuSign] Envelope built: signer={}, signTabs={}, initialTabs={}",
                signerEmail, signHereTabs.size(), initialHereTabs.size());
        return envelope;
    }

    // ── RSA Key Loading ──────────────────────────────────────────────────────

    /**
     * Parses a PEM-encoded RSA private key into a PrivateKey object.
     * Handles both PKCS#8 (BEGIN PRIVATE KEY) and PKCS#1 (BEGIN RSA PRIVATE KEY) formats.
     *
     * DocuSign generates PKCS#1 keys (BEGIN RSA PRIVATE KEY).
     * Java's KeyFactory only accepts PKCS#8 natively, so for PKCS#1 we parse the
     * ASN.1 DER structure directly and build an RSAPrivateCrtKeySpec from the
     * individual key components (modulus, exponents, primes, CRT coefficients).
     */
    private PrivateKey loadPrivateKey(String pem) throws Exception {
        // Normalize: handle literal \n from JSON, then strip PEM delimiters
        String stripped = pem
                .replace("\\n", "\n")                          // literal \n → real newline
                .replace("\\r", "")                            // strip CR
                .replaceAll("-----[A-Za-z ]+-----", "")        // PEM header/footer
                .replaceAll("[^A-Za-z0-9+/=]", "");            // keep only base64 chars

        if (stripped.isEmpty()) {
            throw new IllegalStateException("RSA private key is empty after PEM parsing — " +
                    "check the key format in Admin → Integrations → DocuSign");
        }

        byte[] keyBytes = Base64.getDecoder().decode(stripped);
        log.debug("[DocuSign] Decoded key: {} bytes, first byte=0x{}",
                keyBytes.length, String.format("%02X", keyBytes[0] & 0xFF));

        // Try PKCS#8 first (BEGIN PRIVATE KEY)
        try {
            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
            return KeyFactory.getInstance("RSA").generatePrivate(spec);
        } catch (Exception pkcs8Error) {
            log.debug("[DocuSign] Key is not PKCS#8, trying PKCS#1: {}", pkcs8Error.getMessage());
        }

        // Fall back to PKCS#1 (BEGIN RSA PRIVATE KEY or raw base64 without headers)
        // Parse the ASN.1 DER structure directly to extract RSA components
        try {
            return parsePkcs1PrivateKey(keyBytes);
        } catch (Exception pkcs1Error) {
            log.error("[DocuSign] Key parsing failed for both PKCS#8 and PKCS#1. " +
                    "Key length={} bytes, first byte=0x{}", keyBytes.length,
                    String.format("%02X", keyBytes[0] & 0xFF));
            throw new IllegalStateException(
                    "Unable to parse RSA private key — ensure it is a valid PEM-encoded " +
                    "PKCS#1 (BEGIN RSA PRIVATE KEY) or PKCS#8 (BEGIN PRIVATE KEY) key", pkcs1Error);
        }
    }

    /**
     * Parses a PKCS#1 DER-encoded RSA private key directly into RSAPrivateCrtKeySpec.
     *
     * PKCS#1 RSAPrivateKey ASN.1 structure (RFC 8017):
     *   SEQUENCE {
     *     version           INTEGER,  -- 0
     *     modulus           INTEGER,  -- n
     *     publicExponent    INTEGER,  -- e
     *     privateExponent   INTEGER,  -- d
     *     prime1            INTEGER,  -- p
     *     prime2            INTEGER,  -- q
     *     exponent1         INTEGER,  -- d mod (p-1)
     *     exponent2         INTEGER,  -- d mod (q-1)
     *     coefficient       INTEGER   -- (inverse of q) mod p
     *   }
     */
    private PrivateKey parsePkcs1PrivateKey(byte[] der) throws Exception {
        // Position tracker for DER parsing
        int[] pos = {0};

        // Outer SEQUENCE
        expectTag(der, pos, 0x30);
        readDerLength(der, pos); // consume the length

        // version (INTEGER, should be 0)
        BigInteger version = readDerInteger(der, pos);
        if (version.intValue() != 0) {
            log.warn("[DocuSign] RSA key version={}, expected 0", version);
        }

        // Read all 8 RSA components
        BigInteger modulus         = readDerInteger(der, pos); // n
        BigInteger publicExponent  = readDerInteger(der, pos); // e
        BigInteger privateExponent = readDerInteger(der, pos); // d
        BigInteger prime1          = readDerInteger(der, pos); // p
        BigInteger prime2          = readDerInteger(der, pos); // q
        BigInteger exponent1       = readDerInteger(der, pos); // d mod (p-1)
        BigInteger exponent2       = readDerInteger(der, pos); // d mod (q-1)
        BigInteger coefficient     = readDerInteger(der, pos); // CRT coefficient

        RSAPrivateCrtKeySpec spec = new RSAPrivateCrtKeySpec(
                modulus, publicExponent, privateExponent,
                prime1, prime2, exponent1, exponent2, coefficient);

        return KeyFactory.getInstance("RSA").generatePrivate(spec);
    }

    /** Read a DER INTEGER and return as BigInteger. */
    private BigInteger readDerInteger(byte[] der, int[] pos) {
        expectTag(der, pos, 0x02); // INTEGER tag
        int length = readDerLength(der, pos);
        byte[] value = new byte[length];
        System.arraycopy(der, pos[0], value, 0, length);
        pos[0] += length;
        // DER INTEGER uses two's complement with leading zero for positive values
        // whose high bit is set. BigInteger(byte[]) handles this correctly.
        return new BigInteger(value);
    }

    /** Verify expected DER tag and advance position. */
    private void expectTag(byte[] der, int[] pos, int expectedTag) {
        int actual = der[pos[0]] & 0xFF;
        if (actual != expectedTag) {
            throw new IllegalArgumentException(
                    String.format("Expected DER tag 0x%02X at position %d, got 0x%02X",
                            expectedTag, pos[0], actual));
        }
        pos[0]++;
    }

    /** Read DER length encoding and advance position. Returns the length value. */
    private int readDerLength(byte[] der, int[] pos) {
        int first = der[pos[0]] & 0xFF;
        pos[0]++;

        if (first < 128) {
            return first; // short form
        }

        // Long form: first byte = 0x80 + number of length bytes
        int numBytes = first & 0x7F;
        int length = 0;
        for (int i = 0; i < numBytes; i++) {
            length = (length << 8) | (der[pos[0]] & 0xFF);
            pos[0]++;
        }
        return length;
    }

    // ── Base64 URL Encoding ──────────────────────────────────────────────────

    private String base64UrlEncode(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }

    // ── Token cache ──────────────────────────────────────────────────────────

    private record CachedToken(String accessToken, long expiresAtEpoch) {
        boolean isValid() {
            return Instant.now().getEpochSecond() < expiresAtEpoch;
        }
    }
}
