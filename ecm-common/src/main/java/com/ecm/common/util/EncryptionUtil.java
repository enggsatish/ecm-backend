package com.ecm.common.util;

import lombok.experimental.UtilityClass;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

/**
 * AES-GCM encryption utilities shared across ecm modules.
 * Format: base64(iv) + ":" + base64(ciphertext+authTag)
 */
@UtilityClass
public class EncryptionUtil {

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