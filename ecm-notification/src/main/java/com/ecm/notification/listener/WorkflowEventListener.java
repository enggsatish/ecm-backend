package com.ecm.notification.listener;

import com.ecm.notification.service.EmailQueueService;
import com.ecm.notification.service.NotificationService;
import com.ecm.notification.service.PreferenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Listens to workflow and form events.
 * Creates in-app notifications (immediate) and queues emails (batch every 5 min).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowEventListener {

    private final NotificationService notificationService;
    private final EmailQueueService emailQueueService;
    private final PreferenceService preferenceService;

    @Value("${ecm.notification.app-url:http://localhost:3000}")
    private String appUrl;

    /**
     * Task assigned to a candidate group — notify all users in that role.
     */
    @RabbitListener(queues = "ecm.notification.task.assigned")
    public void onTaskAssigned(Map<String, Object> event) {
        try {
            String taskName      = str(event.get("taskName"));
            String assignedGroup = str(event.get("assignedGroup"));
            String documentName  = str(event.get("documentName"));

            if (assignedGroup == null || assignedGroup.isBlank()) return;

            String title = "New task: " + (taskName != null ? taskName : "Review");
            String body  = documentName != null && !documentName.isBlank()
                    ? "Document '" + documentName + "' requires your review."
                    : "A new task requires your attention.";

            // In-app notification (immediate)
            notificationService.notifyRole(assignedGroup, title, body,
                    "/backoffice/queue", "TASK_ASSIGNED");

            // Queue email (batch) — one per user in the role
            List<String> emails = notificationService.getUserEmailsForRole(assignedGroup);
            Map<String, String> vars = new HashMap<>();
            vars.put("taskName", taskName != null ? taskName : "Review");
            vars.put("documentName", documentName != null ? documentName : "—");
            vars.put("appUrl", appUrl);

            for (String email : emails) {
                emailQueueService.queueFromTemplate(email, "TASK_ASSIGNED", vars);
            }

            log.info("Task assigned: group={}, notified {} users", assignedGroup, emails.size());
        } catch (Exception e) {
            log.warn("Failed to process task.assigned: {}", e.getMessage());
        }
    }

    /**
     * Workflow completed → notify the original submitter.
     */
    @RabbitListener(queues = "ecm.notification.workflow.completed")
    public void onWorkflowCompleted(Map<String, Object> event) {
        try {
            String decision     = str(event.get("decision"));
            String submissionId = str(event.get("submissionId"));
            String comment      = str(event.get("comment"));

            if (submissionId == null) return;

            String submitterEmail = notificationService.getSubmitterEmail(submissionId);
            if (submitterEmail == null) return;

            boolean approved = "APPROVED".equalsIgnoreCase(decision);
            String title = approved ? "Your submission has been approved" : "Your submission has been reviewed";
            String body  = approved
                    ? "Your form submission has been approved and a document has been created."
                    : "Your form submission has been reviewed. Decision: " + decision;

            // In-app (immediate)
            notificationService.notifyUser(submitterEmail, title, body,
                    "/eforms/submissions/mine",
                    approved ? "FORM_APPROVED" : "FORM_REJECTED");

            // Queue email (batch)
            String templateKey = approved ? "FORM_APPROVED" : "FORM_REJECTED";
            Map<String, String> vars = new HashMap<>();
            vars.put("decision", decision != null ? decision : "UNKNOWN");
            vars.put("comment", comment != null ? comment : "");
            vars.put("appUrl", appUrl);

            emailQueueService.queueFromTemplate(submitterEmail, templateKey, vars);

            log.info("Workflow completed: submitter={}, decision={}", submitterEmail, decision);
        } catch (Exception e) {
            log.warn("Failed to process workflow.completed: {}", e.getMessage());
        }
    }

    /**
     * BPMN notification step (from NotificationDelegate).
     */
    @RabbitListener(queues = "ecm.notification.email")
    public void onNotificationEvent(Map<String, Object> event) {
        try {
            String submittedBy  = str(event.get("submittedBy"));
            String activityName = str(event.get("activityName"));

            if (submittedBy != null && !submittedBy.isBlank()) {
                String title = "Workflow update: " + (activityName != null && !activityName.isBlank()
                        ? activityName : "Step completed");
                notificationService.notifyUser(submittedBy, title,
                        "Your submission is progressing through the review workflow.",
                        "/eforms/submissions/mine", "WORKFLOW_UPDATE");
            }
        } catch (Exception e) {
            log.warn("Failed to process notification event: {}", e.getMessage());
        }
    }

    /**
     * Form reviewed (approved/rejected via eforms).
     */
    @RabbitListener(queues = "ecm.notification.form.reviewed")
    public void onFormReviewed(Map<String, Object> event) {
        try {
            String status      = str(event.get("status"));
            String submittedBy = str(event.get("submittedBy"));
            String reviewNotes = str(event.get("reviewNotes"));

            if (submittedBy == null) return;

            String title = "APPROVED".equals(status)
                    ? "Your form has been approved"
                    : "Your form has been reviewed — " + status;

            String body = reviewNotes != null && !reviewNotes.isBlank()
                    ? "Reviewer notes: " + reviewNotes
                    : "Status: " + status;

            // In-app (immediate)
            notificationService.notifyUser(submittedBy, title, body,
                    "/eforms/submissions/mine",
                    "APPROVED".equals(status) ? "FORM_APPROVED" : "FORM_REJECTED");

            // Queue email (batch)
            String templateKey = "APPROVED".equals(status) ? "FORM_APPROVED" : "FORM_REJECTED";
            Map<String, String> vars = new HashMap<>();
            vars.put("decision", status);
            vars.put("comment", reviewNotes != null ? reviewNotes : "");
            vars.put("appUrl", appUrl);
            emailQueueService.queueFromTemplate(submittedBy, templateKey, vars);
        } catch (Exception e) {
            log.warn("Failed to process form.reviewed: {}", e.getMessage());
        }
    }

    private static String str(Object o) {
        return o != null ? o.toString().trim() : null;
    }
}
