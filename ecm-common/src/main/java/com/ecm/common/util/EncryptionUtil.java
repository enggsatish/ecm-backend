package com.ecm.common.util;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;

/**
 * AES-GCM encryption utilities shared across ecm modules.
 * Format: base64(iv) + ":" + base64(ciphertext+authTag)
 */
@Slf4j
@UtilityClass
public class EncryptionUtil {

    /**
     * Every ecm-* service that touches encrypted integration secrets
     * (ecm-admin, ecm-eforms) must resolve to the IDENTICAL master key —
     * ecm-eforms decrypts secrets ecm-admin encrypted, sharing the same DB
     * rows across separate processes. Using one well-known file, rather than
     * a per-service file, is what makes that hold: whichever service starts
     * first creates it, every other service just reads the same bytes back.
     */
    private static final Path DEV_KEY_FILE = Path.of(System.getProperty("user.home"), ".ecm-platform", "dev-master-encrypt-key");

    public static String devKeyFilePath() {
        return DEV_KEY_FILE.toString();
    }

    /**
     * Resolves the AES master key used for encrypting/decrypting integration
     * secrets. Real path: MASTER_ENCRYPT_KEY env var (or ecm.master-encrypt-key
     * system property) — this is what production must set explicitly.
     *
     * Dev-only fallback when neither is set: persist a randomly generated key
     * to DEV_KEY_FILE and reuse it on every subsequent call/restart, instead
     * of each service generating its own ephemeral in-memory key every time
     * it starts. That ephemeral behavior is what silently invalidated every
     * saved Salesforce/DocuSign/AI Gateway secret on every local restart —
     * this removes that failure mode without requiring any manual setup.
     */
    public static synchronized String resolveMasterKeyBase64() {
        String fromEnv = System.getenv("MASTER_ENCRYPT_KEY");
        if (fromEnv == null || fromEnv.isBlank()) fromEnv = System.getProperty("ecm.master-encrypt-key");
        if (fromEnv != null && !fromEnv.isBlank()) return fromEnv;

        try {
            if (Files.exists(DEV_KEY_FILE)) {
                return Files.readString(DEV_KEY_FILE).trim();
            }
            KeyGenerator kg = KeyGenerator.getInstance("AES");
            kg.init(256);
            String generated = Base64.getEncoder().encodeToString(kg.generateKey().getEncoded());
            Files.createDirectories(DEV_KEY_FILE.getParent());
            Files.writeString(DEV_KEY_FILE, generated);
            try {
                Files.setPosixFilePermissions(DEV_KEY_FILE, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // Non-POSIX filesystem (e.g. Windows) — best effort only.
            }
            return generated;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to resolve or persist a local dev encryption key at " + DEV_KEY_FILE, e);
        }
    }

    public static String decryptAesGcm(String encryptedValue) {
        String masterKeyB64 = System.getenv("MASTER_ENCRYPT_KEY");
        if (masterKeyB64 == null) {
            masterKeyB64 = System.getProperty("ecm.master-encrypt-key");
        }
        if (masterKeyB64 == null || masterKeyB64.isBlank()) {
            throw new IllegalStateException("MASTER_ENCRYPT_KEY not configured");
        }
        try {
            String[] parts    = encryptedValue.split(":", 2);
            byte[] iv         = Base64.getDecoder().decode(parts[0]);
            byte[] ciphertext = Base64.getDecoder().decode(parts[1]);
            byte[] keyBytes   = Base64.getDecoder().decode(masterKeyB64);
            Cipher cipher     = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(keyBytes, "AES"),
                    new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(ciphertext));
        } catch (Exception e) {
            throw new RuntimeException("AES-GCM decryption failed: " + e.getMessage(), e);
        }
    }
}