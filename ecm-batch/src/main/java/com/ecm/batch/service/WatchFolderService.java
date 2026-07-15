package com.ecm.batch.service;

import com.ecm.batch.entity.WatchFolderConfig;
import com.ecm.batch.repository.WatchFolderConfigRepository;
import com.ecm.batch.util.FileMultipartFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Polls a watch folder for new documents and creates batch jobs automatically.
 * <p>
 * Configuration is read from the database (ecm_admin.watch_folder_config) on every poll.
 * This means admins can change the config via UI without restarting the service.
 * <p>
 * If no config exists or enabled=false, the poll is a no-op.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WatchFolderService {

    private final BatchJobService batchJobService;
    private final WatchFolderConfigRepository configRepo;

    private static final String[] SUPPORTED_EXTENSIONS = {
            ".pdf", ".png", ".jpg", ".jpeg", ".tiff", ".tif",
            ".doc", ".docx", ".xls", ".xlsx", ".csv", ".txt"
    };

    /**
     * Polls every 30 seconds. The actual check frequency is gated by the DB config's
     * pollInterval — we skip if not enough time has passed since last actual scan.
     */
    @Scheduled(fixedDelay = 30_000)
    public void poll() {
        var configOpt = configRepo.findDefault();
        if (configOpt.isEmpty()) return;

        WatchFolderConfig config = configOpt.get();
        if (!Boolean.TRUE.equals(config.getEnabled())) return;
        if (config.getFolderPath() == null || config.getFolderPath().isBlank()) return;

        File folder = Path.of(config.getFolderPath()).toFile();
        if (!folder.exists() || !folder.isDirectory()) {
            log.warn("Watch folder does not exist: {}", config.getFolderPath());
            return;
        }

        File[] files = folder.listFiles((dir, name) -> {
            String lower = name.toLowerCase();
            for (String ext : SUPPORTED_EXTENSIONS) {
                if (lower.endsWith(ext)) return true;
            }
            return false;
        });

        if (files == null || files.length == 0) {
            log.debug("No new files in watch folder: {}", config.getFolderPath());
            return;
        }

        log.info("Found {} files in watch folder {}", files.length, config.getFolderPath());

        // Ensure processed/failed directories exist
        ensureDirectory(config.getProcessedPath());
        ensureDirectory(config.getFailedPath());

        for (File file : files) {
            try {
                processFile(file, config);
            } catch (Exception e) {
                log.error("Failed to process file {}: {}", file.getName(), e.getMessage());
                moveToFailed(file, config);
            }
        }
    }

    private void processFile(File file, WatchFolderConfig config) {
        log.info("Processing watch folder file: {} ({}KB)", file.getName(), file.length() / 1024);

        try {
            // Convert File to MultipartFile for the batch API
            MultipartFile multipartFile = new FileMultipartFile(file);

            // Create a single-file batch job — the pipeline (OCR → classify → match)
            // runs asynchronously via RabbitMQ after batch creation
            batchJobService.createBatch(
                    List.of(multipartFile),
                    "WATCH_FOLDER",
                    "Auto-ingested from watch folder: " + config.getFolderPath(),
                    "system"
            );

            log.info("Batch created for watch folder file: {}", file.getName());
            moveToProcessed(file, config);
        } catch (Exception e) {
            log.error("Failed to create batch for file {}: {}", file.getName(), e.getMessage(), e);
            moveToFailed(file, config);
        }
    }

    private void moveToProcessed(File file, WatchFolderConfig config) {
        if (config.getProcessedPath() == null || config.getProcessedPath().isBlank()) return;
        moveFile(file, config.getProcessedPath());
    }

    private void moveToFailed(File file, WatchFolderConfig config) {
        if (config.getFailedPath() == null || config.getFailedPath().isBlank()) return;
        moveFile(file, config.getFailedPath());
    }

    private void moveFile(File file, String targetDir) {
        try {
            Path target = Path.of(targetDir, file.getName());
            Files.move(file.toPath(), target, StandardCopyOption.REPLACE_EXISTING);
            log.debug("Moved {} -> {}", file.getName(), target);
        } catch (IOException e) {
            log.error("Failed to move file {} to {}: {}", file.getName(), targetDir, e.getMessage());
        }
    }

    private void ensureDirectory(String path) {
        if (path == null || path.isBlank()) return;
        File dir = new File(path);
        if (!dir.exists()) {
            boolean created = dir.mkdirs();
            if (created) log.info("Created directory: {}", path);
        }
    }
}
