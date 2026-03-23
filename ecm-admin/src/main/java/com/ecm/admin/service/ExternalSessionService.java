package com.ecm.admin.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

/**
 * Lightweight session token service for external participants.
 *
 * Issues HMAC-SHA256 signed tokens after OTP verification.
 * Token format: base64({participantId}:{caseId}:{ip}:{expiresEpoch}).base64(signature)
 *
 * NOT a full JWT — intentionally simple. External sessions are short-lived (1 hour)
 * and bound to a specific participant + case + IP.
 */
@Slf4j
@Service
public class ExternalSessionService {

    @Value("${ecm.external.session-secret:ecm-external-session-secret-change-in-production}")
    private String secret;

    @Value("${ecm.external.session-ttl-minutes:60}")
    private int sessionTtlMinutes;

    /**
     * Creates a session token for a verified external participant.
     */
    public String createToken(int participantId, String caseId, String ipAddress) {
        long expiresAt = Instant.now().plusSeconds(sessionTtlMinutes * 60L).getEpochSecond();
        String payload = participantId + "|" + caseId + "|" + ipAddress + "|" + expiresAt;
        String encodedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String signature = sign(payload);
        return encodedPayload + "." + signature;
    }

    /**
     * Validates a session token. Returns parsed claims or null if invalid.
     */
    public SessionClaims validate(String token, String currentIp) {
        if (token == null || !token.contains(".")) return null;

        try {
            String[] parts = token.split("\\.", 2);
            String payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            String expectedSig = sign(payload);

            if (!expectedSig.equals(parts[1])) {
                log.debug("External session: invalid signature");
                return null;
            }

            String[] fields = payload.split("\\|", 4);
            if (fields.length != 4) return null;

            int participantId = Integer.parseInt(fields[0]);
            String caseId = fields[1];
            String boundIp = fields[2];
            long expiresAt = Long.parseLong(fields[3]);

            if (Instant.now().getEpochSecond() > expiresAt) {
                log.debug("External session: expired");
                return null;
            }

            if (!boundIp.equals(currentIp)) {
                log.warn("External session: IP mismatch — bound={}, current={}", boundIp, currentIp);
                return null;
            }

            return new SessionClaims(participantId, caseId, boundIp, expiresAt);
        } catch (Exception e) {
            log.debug("External session: parse error — {}", e.getMessage());
            return null;
        }
    }

    private String sign(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("HMAC signing failed", e);
        }
    }

    public record SessionClaims(int participantId, String caseId, String ipAddress, long expiresAt) {}
}
