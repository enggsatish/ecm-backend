package com.ecm.batch.controller;

import com.ecm.batch.entity.WatchFolderConfig;
import com.ecm.batch.repository.WatchFolderConfigRepository;
import com.ecm.common.model.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Batch processing configuration — SUPER_ADMIN only.
 *
 * Two config stores:
 *   1. ecm_admin.watch_folder_config — dedicated table for folder paths
 *   2. ecm_admin.tenant_config — key-value for everything else (thresholds, roles, notifications)
 */
@RestController
@RequestMapping("/api/batch/config")
@RequiredArgsConstructor
@Slf4j
public class BatchConfigController {

    private final WatchFolderConfigRepository watchFolderRepo;
    private final JdbcTemplate jdbc;

    // ── Tenant config keys for batch settings ────────────────────────────────
    private static final String KEY_CONFIDENCE_THRESHOLD = "batch.confidence_threshold";
    private static final String KEY_REVIEW_ROLE = "batch.review_role";
    private static final String KEY_FAILURE_NOTIFY_ROLE = "batch.failure_notify_role";
    private static final String KEY_SPOT_CHECK_ROLE = "batch.spot_check_role";
    private static final String KEY_MAX_BATCH_SIZE = "batch.max_batch_size";
    private static final String KEY_NOTIFY_ON_FAILURE = "batch.notify_on_failure";
    private static final String KEY_NOTIFY_ON_REVIEW = "batch.notify_on_review";
    private static final String KEY_AUTO_CREATE_FOLDERS = "batch.auto_create_folders";

    // ── GET /api/batch/config/all ────────────────────────────────────────────
    @GetMapping("/all")
    @PreAuthorize("hasPermission(null, 'batch:admin')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getAllConfig() {
        Map<String, Object> config = new HashMap<>();

        // Watch folder
        var wf = watchFolderRepo.findDefault().orElse(null);
        config.put("watchFolder", wf != null ? Map.of(
                "enabled", wf.getEnabled(),
                "watchPath", wf.getFolderPath() != null ? wf.getFolderPath() : "",
                "processedPath", wf.getProcessedPath() != null ? wf.getProcessedPath() : "",
                "failedPath", wf.getFailedPath() != null ? wf.getFailedPath() : "",
                "pollIntervalSeconds", wf.getPollInterval() != null ? wf.getPollInterval() : 300
        ) : Map.of(
                "enabled", false, "watchPath", "", "processedPath", "",
                "failedPath", "", "pollIntervalSeconds", 300
        ));

        // Processing settings from tenant_config
        config.put("confidenceThreshold", getConfigValue(KEY_CONFIDENCE_THRESHOLD, "90.0"));
        config.put("maxBatchSize", getConfigValue(KEY_MAX_BATCH_SIZE, "500"));
        config.put("autoCreateFolders", getConfigValue(KEY_AUTO_CREATE_FOLDERS, "true"));

        // Role assignments
        config.put("reviewRole", getConfigValue(KEY_REVIEW_ROLE, "ECM_BACKOFFICE"));
        config.put("failureNotifyRole", getConfigValue(KEY_FAILURE_NOTIFY_ROLE, "ECM_ADMIN"));
        config.put("spotCheckRole", getConfigValue(KEY_SPOT_CHECK_ROLE, "ECM_REVIEWER"));

        // Notification settings
        config.put("notifyOnFailure", getConfigValue(KEY_NOTIFY_ON_FAILURE, "true"));
        config.put("notifyOnReview", getConfigValue(KEY_NOTIFY_ON_REVIEW, "true"));

        return ResponseEntity.ok(ApiResponse.ok(config));
    }

    // ── PUT /api/batch/config/all ────────────────────────────────────────────
    @PutMapping("/all")
    @PreAuthorize("hasPermission(null, 'batch:admin')")
    public ResponseEntity<ApiResponse<String>> saveAllConfig(@RequestBody Map<String, Object> body) {

        // Watch folder
        if (body.containsKey("watchFolder")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> wf = (Map<String, Object>) body.get("watchFolder");
            saveWatchFolder(wf);
        }

        // Processing settings
        if (body.containsKey("confidenceThreshold"))
            upsertConfig(KEY_CONFIDENCE_THRESHOLD, String.valueOf(body.get("confidenceThreshold")));
        if (body.containsKey("maxBatchSize"))
            upsertConfig(KEY_MAX_BATCH_SIZE, String.valueOf(body.get("maxBatchSize")));
        if (body.containsKey("autoCreateFolders"))
            upsertConfig(KEY_AUTO_CREATE_FOLDERS, String.valueOf(body.get("autoCreateFolders")));

        // Role assignments
        if (body.containsKey("reviewRole"))
            upsertConfig(KEY_REVIEW_ROLE, (String) body.get("reviewRole"));
        if (body.containsKey("failureNotifyRole"))
            upsertConfig(KEY_FAILURE_NOTIFY_ROLE, (String) body.get("failureNotifyRole"));
        if (body.containsKey("spotCheckRole"))
            upsertConfig(KEY_SPOT_CHECK_ROLE, (String) body.get("spotCheckRole"));

        // Notification settings
        if (body.containsKey("notifyOnFailure"))
            upsertConfig(KEY_NOTIFY_ON_FAILURE, String.valueOf(body.get("notifyOnFailure")));
        if (body.containsKey("notifyOnReview"))
            upsertConfig(KEY_NOTIFY_ON_REVIEW, String.valueOf(body.get("notifyOnReview")));

        log.info("Batch config saved by admin: {}", body.keySet());
        return ResponseEntity.ok(ApiResponse.ok("Batch configuration saved", "Configuration saved successfully"));
    }

    // ── Legacy endpoints (keep backward compat) ─────────────────────────────

    @GetMapping("/watch-folder")
    @PreAuthorize("hasPermission(null, 'batch:admin')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getWatchFolderConfig() {
        var config = watchFolderRepo.findDefault().orElse(null);
        Map<String, Object> response = config != null ? Map.of(
                "enabled", config.getEnabled(),
                "watchPath", config.getFolderPath() != null ? config.getFolderPath() : "",
                "processedPath", config.getProcessedPath() != null ? config.getProcessedPath() : "",
                "failedPath", config.getFailedPath() != null ? config.getFailedPath() : "",
                "pollIntervalSeconds", config.getPollInterval() != null ? config.getPollInterval() : 300
        ) : Map.of("enabled", false, "watchPath", "", "processedPath", "", "failedPath", "", "pollIntervalSeconds", 300);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PutMapping("/watch-folder")
    @PreAuthorize("hasPermission(null, 'batch:admin')")
    public ResponseEntity<ApiResponse<String>> updateWatchFolderConfig(@RequestBody Map<String, Object> body) {
        saveWatchFolder(body);
        return ResponseEntity.ok(ApiResponse.ok("Watch folder configuration saved", "Saved"));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void saveWatchFolder(Map<String, Object> body) {
        var config = watchFolderRepo.findDefault()
                .orElseGet(() -> WatchFolderConfig.builder().tenantId("default").build());

        if (body.containsKey("watchPath"))
            config.setFolderPath((String) body.get("watchPath"));
        if (body.containsKey("processedPath"))
            config.setProcessedPath((String) body.get("processedPath"));
        if (body.containsKey("failedPath"))
            config.setFailedPath((String) body.get("failedPath"));
        if (body.containsKey("enabled"))
            config.setEnabled(Boolean.TRUE.equals(body.get("enabled")));
        if (body.containsKey("pollIntervalSeconds"))
            config.setPollInterval(((Number) body.get("pollIntervalSeconds")).intValue());

        watchFolderRepo.save(config);
        log.info("Watch folder config saved: path={}, enabled={}", config.getFolderPath(), config.getEnabled());
    }

    private String getConfigValue(String key, String defaultValue) {
        try {
            var result = jdbc.queryForObject(
                    "SELECT value FROM ecm_admin.tenant_config WHERE key = ?",
                    String.class, key);
            return result != null ? result : defaultValue;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private void upsertConfig(String key, String value) {
        jdbc.update("""
            INSERT INTO ecm_admin.tenant_config (key, value, description, updated_at)
            VALUES (?, ?, ?, NOW())
            ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = NOW()
            """, key, value, "Batch processing config — managed via Admin > Batch Settings");
    }
}
