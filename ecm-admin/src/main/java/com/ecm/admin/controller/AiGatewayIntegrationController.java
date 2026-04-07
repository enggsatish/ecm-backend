package com.ecm.admin.controller;

import com.ecm.admin.dto.AiGatewayIntegrationDto;
import com.ecm.admin.entity.TenantConfig;
import com.ecm.admin.repository.TenantConfigRepository;
import com.ecm.common.model.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Admin API for the AI Gateway integration — URL and HMAC webhook secret.
 *
 * <p>The AI Gateway rejects unsigned webhook requests (see its
 * {@code WebhookAuthFilter}). ECM OCR's RAG push uses the HMAC secret configured
 * here to sign its outbound {@code /api/webhook/rag-ingest} calls. Rotation is
 * a two-step manual workflow:
 * <ol>
 *   <li>Admin clicks "Rotate Secret" in the AI Gateway admin UI → copies the new hex value</li>
 *   <li>Admin pastes it here → saves → OCR secret cache expires within 60 seconds</li>
 * </ol>
 *
 * <p>Reads never return the plaintext secret — only a configured flag and a masked
 * preview (last 4 chars). See {@link AiGatewayIntegrationDto} for details.
 */
@RestController
@RequestMapping("/api/admin/integrations/ai-gateway")
public class AiGatewayIntegrationController {

    private static final Logger log = LoggerFactory.getLogger(AiGatewayIntegrationController.class);

    private static final String KEY_URL    = "ai.gateway.webhook.url";
    private static final String KEY_SECRET = "ai.gateway.webhook.hmac.secret";

    private final TenantConfigRepository repo;

    public AiGatewayIntegrationController(TenantConfigRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    @PreAuthorize("hasPermission(null, 'admin:configure')")
    public ResponseEntity<ApiResponse<AiGatewayIntegrationDto.Response>> get() {
        return ResponseEntity.ok(ApiResponse.ok(buildResponse()));
    }

    @PutMapping
    @Transactional
    @PreAuthorize("hasPermission(null, 'admin:configure')")
    public ResponseEntity<ApiResponse<AiGatewayIntegrationDto.Response>> update(
            @RequestBody AiGatewayIntegrationDto.UpdateRequest req) {

        // URL: null means "leave unchanged"; empty string means "clear"
        if (req.getUrl() != null) {
            upsert(KEY_URL, req.getUrl().trim(),
                    "AI Gateway webhook endpoint (e.g. http://localhost:8090/api/webhook/rag-ingest)");
        }

        // HMAC secret: null/blank means "leave unchanged" (lets admin update URL without re-entering secret).
        // To clear, send an explicit sentinel — not supported today, add a DELETE endpoint if needed.
        String newSecret = req.getHmacSecret();
        if (newSecret != null && !newSecret.isBlank()) {
            upsert(KEY_SECRET, newSecret.trim(),
                    "HMAC-SHA256 shared secret for signing AI Gateway webhook calls (paste from AI Gateway admin UI)");
            // Never log the actual value — just that it was rotated
            log.info("AI Gateway webhook HMAC secret updated by admin");
        }

        return ResponseEntity.ok(ApiResponse.ok(buildResponse(), "AI Gateway integration saved"));
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private void upsert(String key, String value, String description) {
        TenantConfig tc = repo.findById(key).orElseGet(() -> {
            TenantConfig n = new TenantConfig();
            n.setKey(key);
            return n;
        });
        tc.setValue(value);
        if (tc.getDescription() == null || tc.getDescription().isBlank()) {
            tc.setDescription(description);
        }
        tc.setUpdatedAt(OffsetDateTime.now());
        repo.save(tc);
    }

    private AiGatewayIntegrationDto.Response buildResponse() {
        AiGatewayIntegrationDto.Response out = new AiGatewayIntegrationDto.Response();

        Optional<TenantConfig> urlRow = repo.findById(KEY_URL);
        urlRow.ifPresent(tc -> out.setUrl(tc.getValue()));

        Optional<TenantConfig> secretRow = repo.findById(KEY_SECRET);
        if (secretRow.isPresent() && secretRow.get().getValue() != null && !secretRow.get().getValue().isBlank()) {
            TenantConfig tc = secretRow.get();
            out.setHmacSecretConfigured(true);
            out.setHmacSecretPreview(maskPreview(tc.getValue()));
            out.setHmacSecretUpdatedAt(tc.getUpdatedAt());
        } else {
            out.setHmacSecretConfigured(false);
        }

        return out;
    }

    /** Returns a masked form of the secret showing only the last 4 chars. */
    private String maskPreview(String value) {
        if (value == null || value.length() <= 4) return "••••";
        return "••••" + value.substring(value.length() - 4);
    }
}
