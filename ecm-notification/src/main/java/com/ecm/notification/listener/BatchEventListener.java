package com.ecm.notification.listener;

import com.ecm.notification.service.EmailQueueService;
import com.ecm.notification.service.NotificationService;
import com.ecm.notification.service.PreferenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Listens to batch processing events from ecm-batch.
 *
 * - batch.completed → notify the batch creator (in-app + queued email)
 * - batch.failed    → notify the batch creator (in-app + queued email)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BatchEventListener {

    private final NotificationService notificationService;
    private final EmailQueueService emailQueueService;
    private final PreferenceService preferenceService;

    @Value("${ecm.notification.app-url:http://localhost:3000}")
    private String appUrl;

    /**
     * Batch job completed — all items processed (some may have failed).
     */
    @RabbitListener(queues = "ecm.notification.batch.completed")
    public void onBatchCompleted(Map<String, Object> event) {
        try {
            String batchId     = str(event.get("batchId"));
            String batchName   = str(event.get("batchName"));
            String createdBy   = str(event.get("createdBy"));
            int totalCount     = intVal(event.get("totalCount"));
            int successCount   = intVal(event.get("successCount"));
            int failedCount    = intVal(event.get("failedCount"));

            if (createdBy == null || createdBy.isBlank()) return;

            String title = "Batch complete: " + (batchName != null ? batchName : "Batch job");
            String body = totalCount + " items processed — " + successCount + " succeeded"
                    + (failedCount > 0 ? ", " + failedCount + " failed" : "") + ".";

            // In-app
            if (preferenceService.isEnabled(createdBy, "BATCH_COMPLETED", "IN_APP")) {
                String link = batchId != null ? "/batch/" + batchId : "/batch";
                notificationService.notifyUser(createdBy, title, body, link, "BATCH_COMPLETED");
            }

            // Queued email
            if (preferenceService.isEnabled(createdBy, "BATCH_COMPLETED", "EMAIL")) {
                Map<String, String> vars = new HashMap<>();
                vars.put("batchName", batchName != null ? batchName : "Batch job");
                vars.put("totalCount", String.valueOf(totalCount));
                vars.put("successCount", String.valueOf(successCount));
                vars.put("failedCount", String.valueOf(failedCount));
                vars.put("appUrl", appUrl);
                emailQueueService.queueFromTemplate(createdBy, "BATCH_COMPLETED", vars);
            }

            log.info("Batch completed notification: creator={}, total={}, success={}, failed={}",
                    createdBy, totalCount, successCount, failedCount);
        } catch (Exception e) {
            log.warn("Failed to process batch.completed notification: {}", e.getMessage());
        }
    }

    /**
     * Batch job failed — entire batch could not be processed.
     */
    @RabbitListener(queues = "ecm.notification.batch.failed")
    public void onBatchFailed(Map<String, Object> event) {
        try {
            String batchId      = str(event.get("batchId"));
            String batchName    = str(event.get("batchName"));
            String createdBy    = str(event.get("createdBy"));
            int failedCount     = intVal(event.get("failedCount"));
            String errorSummary = str(event.get("errorSummary"));

            if (createdBy == null || createdBy.isBlank()) return;

            String title = "Batch failed: " + (batchName != null ? batchName : "Batch job");
            String body = failedCount + " item" + (failedCount != 1 ? "s" : "") + " failed."
                    + (errorSummary != null ? " " + errorSummary : "");

            // In-app
            if (preferenceService.isEnabled(createdBy, "BATCH_FAILURE", "IN_APP")) {
                String link = batchId != null ? "/batch/" + batchId : "/batch";
                notificationService.notifyUser(createdBy, title, body, link, "BATCH_FAILURE");
            }

            // Queued email
            if (preferenceService.isEnabled(createdBy, "BATCH_FAILURE", "EMAIL")) {
                Map<String, String> vars = new HashMap<>();
                vars.put("batchName", batchName != null ? batchName : "Batch job");
                vars.put("failedCount", String.valueOf(failedCount));
                vars.put("errorSummary", errorSummary != null ? errorSummary : "Processing failed");
                vars.put("appUrl", appUrl);
                emailQueueService.queueFromTemplate(createdBy, "BATCH_FAILURE", vars);
            }

            log.info("Batch failed notification: creator={}, failedCount={}", createdBy, failedCount);
        } catch (Exception e) {
            log.warn("Failed to process batch.failed notification: {}", e.getMessage());
        }
    }

    private static String str(Object o) {
        return o != null ? o.toString().trim() : null;
    }

    private static int intVal(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(o.toString()); } catch (NumberFormatException e) { return 0; }
    }
}
