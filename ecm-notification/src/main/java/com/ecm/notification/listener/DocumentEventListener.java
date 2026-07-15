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
import java.util.List;
import java.util.Map;

/**
 * Listens to document events from ecm-document.
 *
 * - document.classified → notify the uploader (in-app + queued email)
 * - document.classification.stale → notify all reviewers (in-app + queued email)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentEventListener {

    private final NotificationService notificationService;
    private final EmailQueueService emailQueueService;
    private final PreferenceService preferenceService;

    @Value("${ecm.notification.app-url:http://localhost:3000}")
    private String appUrl;

    /**
     * A document was classified (auto or manual).
     * Notify the person who uploaded it.
     */
    @RabbitListener(queues = "ecm.notification.document.classified")
    public void onDocumentClassified(Map<String, Object> event) {
        try {
            String documentId     = str(event.get("documentId"));
            String documentName   = str(event.get("documentName"));
            String categoryName   = str(event.get("categoryName"));
            String uploadedBy     = str(event.get("uploadedBy"));
            String customerName   = str(event.get("customerName"));
            String source         = str(event.get("classificationSource"));

            if (uploadedBy == null || uploadedBy.isBlank()) {
                log.debug("No uploadedBy for document.classified — skipping notification");
                return;
            }

            String displayCategory = categoryName != null ? categoryName : "Unknown";
            String displaySource = source != null ? source : "classified";
            String title = "Document classified: " + displayCategory;
            String body = (documentName != null ? "'" + documentName + "'" : "Your document")
                    + " has been " + displaySource.toLowerCase() + " as " + displayCategory + ".";

            // In-app notification (respects user preference)
            if (preferenceService.isEnabled(uploadedBy, "DOCUMENT_CLASSIFIED", "IN_APP")) {
                String link = documentId != null ? "/documents?highlight=" + documentId : "/documents";
                notificationService.notifyUser(uploadedBy, title, body, link, "DOCUMENT_CLASSIFIED");
            }

            // Queued email (respects user preference)
            if (preferenceService.isEnabled(uploadedBy, "DOCUMENT_CLASSIFIED", "EMAIL")) {
                Map<String, String> vars = new HashMap<>();
                vars.put("documentName", documentName != null ? documentName : "Document");
                vars.put("categoryName", displayCategory);
                vars.put("customerName", customerName != null ? customerName : "—");
                vars.put("classificationSource", displaySource);
                vars.put("appUrl", appUrl);
                emailQueueService.queueFromTemplate(uploadedBy, "DOCUMENT_CLASSIFIED", vars);
            }

            log.info("Document classified notification: uploader={}, category={}, source={}",
                    uploadedBy, displayCategory, displaySource);
        } catch (Exception e) {
            log.warn("Failed to process document.classified notification: {}", e.getMessage());
        }
    }

    /**
     * Documents have been sitting unclassified for too long.
     * Notify all users with the ECM_REVIEWER role.
     */
    @RabbitListener(queues = "ecm.notification.document.stale")
    public void onClassificationStale(Map<String, Object> event) {
        try {
            Object countObj = event.get("count");
            String oldestAge = str(event.get("oldestAge"));

            int count = 0;
            if (countObj != null) {
                count = ((Number) countObj).intValue();
            }

            if (count == 0) return;

            String title = count + " document" + (count > 1 ? "s" : "") + " awaiting classification";
            String body = count + " document" + (count > 1 ? "s have" : " has")
                    + " been unclassified for " + (oldestAge != null ? oldestAge : "several hours") + ".";

            // In-app notification to all reviewers
            notificationService.notifyRole("ECM_REVIEWER", title, body,
                    "/review/classification", "CLASSIFICATION_REVIEW");

            // Queued email to all reviewers
            List<String> reviewerEmails = notificationService.getUserEmailsForRole("ECM_REVIEWER");
            Map<String, String> vars = new HashMap<>();
            vars.put("count", String.valueOf(count));
            vars.put("oldestAge", oldestAge != null ? oldestAge : "several hours");
            vars.put("appUrl", appUrl);

            for (String email : reviewerEmails) {
                if (preferenceService.isEnabled(email, "CLASSIFICATION_REVIEW", "EMAIL")) {
                    emailQueueService.queueFromTemplate(email, "CLASSIFICATION_STALE", vars);
                }
            }

            log.info("Stale classification alert: {} documents, notified {} reviewers",
                    count, reviewerEmails.size());
        } catch (Exception e) {
            log.warn("Failed to process document.classification.stale notification: {}", e.getMessage());
        }
    }

    private static String str(Object o) {
        return o != null ? o.toString().trim() : null;
    }
}
