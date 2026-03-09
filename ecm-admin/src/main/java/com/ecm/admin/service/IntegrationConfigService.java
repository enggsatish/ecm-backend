package com.ecm.admin.service;

import com.ecm.admin.dto.IntegrationConfigDto;
import com.ecm.admin.dto.IntegrationConfigDto.*;
import com.ecm.admin.entity.IntegrationConfig;
import com.ecm.admin.repository.IntegrationConfigRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Manages third-party integration credentials with AES-GCM encryption at rest.
 *
 * MASTER_ENCRYPT_KEY env var must be a 32-byte (256-bit) base64-encoded AES key.
 * Generate one with: openssl rand -base64 32
 *
 * If the env var is absent (dev mode), a random ephemeral key is used —
 * secrets stored in one run cannot be decrypted after restart.
 * Always set MASTER_ENCRYPT_KEY in production.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class IntegrationConfigService {

    private static final String SYSTEM_DOCUSIGN = "DOCUSIGN";
    private static final String AES_ALGO        = "AES";
    private static final String AES_GCM_ALGO    = "AES/GCM/NoPadding";
    private static final int    GCM_IV_BYTES    = 12;
    private static final int    GCM_TAG_BITS    = 128;

    private final IntegrationConfigRepository repo;

    @Value("${ecm.master-encrypt-key:#{null}}")
    private String masterKeyBase64;

    private SecretKey masterKey;

    @PostConstruct
    void initKey() {
        if (masterKeyBase64 != null && !masterKeyBase64.isBlank()) {
            byte[] keyBytes = Base64.getDecoder().decode(masterKeyBase64);
            masterKey = new SecretKeySpec(keyBytes, AES_ALGO);
            log.info("[IntegrationConfig] Using configured MASTER_ENCRYPT_KEY");
        } else {
            try {
                KeyGenerator kg = KeyGenerator.getInstance(AES_ALGO);
                kg.init(256);
                masterKey = kg.generateKey();
                log.warn("[IntegrationConfig] MASTER_ENCRYPT_KEY not set — using ephemeral key. " +
                        "Secrets will not survive restart. Set ecm.master-encrypt-key in production.");
            } catch (Exception e) {
                throw new IllegalStateException("Failed to generate ephemeral AES key", e);
            }
        }
    }

    // ── DocuSign ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public DocuSignConfigResponse getDocuSign(String tenantId) {
        IntegrationConfig cfg = findOrEmpty(tenantId, SYSTEM_DOCUSIGN);
        Map<String, Object> c = cfg.getConfig();
        Map<String, Object> s = cfg.getSecrets();
        return new DocuSignConfigResponse(
                cfg.getEnabled(),
                str(c, "base_url"),
                str(c, "auth_server"),
                str(c, "account_id"),
                str(c, "integration_key"),
                str(c, "impersonated_user_id"),
                IntegrationConfigDto.masked(s.get("rsa_private_key")),
                IntegrationConfigDto.masked(s.get("webhook_hmac_secret")),
                cfg.getTestStatus(),
                cfg.getTestedAt()
        );
    }

    public DocuSignConfigResponse saveDocuSign(String tenantId, DocuSignConfigRequest req) {
        IntegrationConfig cfg = findOrEmpty(tenantId, SYSTEM_DOCUSIGN);

        // Update non-sensitive config
        Map<String, Object> c = new HashMap<>(cfg.getConfig());
        putIfNotNull(c, "base_url",              req.baseUrl());
        putIfNotNull(c, "auth_server",           req.authServer());
        putIfNotNull(c, "account_id",            req.accountId());
        putIfNotNull(c, "integration_key",       req.integrationKey());
        putIfNotNull(c, "impersonated_user_id",  req.impersonatedUserId());
        cfg.setConfig(c);

        // Update secrets (encrypt only if new value supplied)
        Map<String, Object> s = new HashMap<>(cfg.getSecrets());
        if (IntegrationConfigDto.shouldUpdateSecret(req.rsaPrivateKey())) {
            s.put("rsa_private_key", encrypt(req.rsaPrivateKey()));
        }
        if (IntegrationConfigDto.shouldUpdateSecret(req.webhookHmacSecret())) {
            s.put("webhook_hmac_secret", encrypt(req.webhookHmacSecret()));
        }
        cfg.setSecrets(s);

        cfg.setEnabled(req.enabled());
        cfg.setUpdatedAt(OffsetDateTime.now());
        repo.save(cfg);

        return getDocuSign(tenantId);
    }

    /**
     * Updates test_status and tested_at after a connection test.
     * Called by DocuSignConfigController.
     */
    public void recordTestResult(String tenantId, boolean success, String message) {
        IntegrationConfig cfg = findOrEmpty(tenantId, SYSTEM_DOCUSIGN);
        cfg.setTestStatus(success ? "OK" : "FAILED");
        cfg.setTestedAt(OffsetDateTime.now());
        cfg.setUpdatedAt(OffsetDateTime.now());
        repo.save(cfg);
    }

    /**
     * Returns the decrypted plain-text value of a DocuSign secret field.
     * Used internally by DocuSignService.
     */
    @Transactional(readOnly = true)
    public String getDocuSignSecret(String tenantId, String fieldName) {
        IntegrationConfig cfg = findOrEmpty(tenantId, SYSTEM_DOCUSIGN);
        Object encrypted = cfg.getSecrets().get(fieldName);
        if (encrypted == null) return null;
        return decrypt(String.valueOf(encrypted));
    }

    @Transactional(readOnly = true)
    public String getDocuSignConfigField(String tenantId, String fieldName) {
        IntegrationConfig cfg = findOrEmpty(tenantId, SYSTEM_DOCUSIGN);
        return str(cfg.getConfig(), fieldName);
    }

    @Transactional(readOnly = true)
    public boolean isDocuSignEnabled(String tenantId) {
        return findOrEmpty(tenantId, SYSTEM_DOCUSIGN).getEnabled();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private IntegrationConfig findOrEmpty(String tenantId, String systemKey) {
        return repo.findByTenantIdAndSystemKey(tenantId, systemKey)
                .orElseGet(() -> IntegrationConfig.builder()
                        .tenantId(tenantId)
                        .systemKey(systemKey)
                        .displayName(systemKey)
                        .enabled(false)
                        .config(new HashMap<>())
                        .secrets(new HashMap<>())
                        .build());
    }

    private static String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? String.valueOf(v) : null;
    }

    private static void putIfNotNull(Map<String, Object> map, String key, Object value) {
        if (value != null) map.put(key, value);
    }

    // ── AES-GCM encrypt / decrypt ─────────────────────────────────────────

    /**
     * Encrypts a plaintext string.
     * @return "base64(iv):base64(ciphertext)"
     */
    String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(AES_GCM_ALGO);
            cipher.init(Cipher.ENCRYPT_MODE, masterKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes());

            return Base64.getEncoder().encodeToString(iv)
                    + ":"
                    + Base64.getEncoder().encodeToString(ciphertext);
        } catch (Exception e) {
            throw new IllegalStateException("AES-GCM encryption failed", e);
        }
    }

    /**
     * Decrypts a value produced by {@link #encrypt(String)}.
     */
    String decrypt(String encryptedValue) {
        try {
            String[] parts = encryptedValue.split(":", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("Invalid encrypted value format");
            }
            byte[] iv         = Base64.getDecoder().decode(parts[0]);
            byte[] ciphertext = Base64.getDecoder().decode(parts[1]);

            Cipher cipher = Cipher.getInstance(AES_GCM_ALGO);
            cipher.init(Cipher.DECRYPT_MODE, masterKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext);
        } catch (Exception e) {
            throw new IllegalStateException("AES-GCM decryption failed", e);
        }
    }
}