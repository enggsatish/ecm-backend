package com.ecm.notification.listener;

import com.ecm.notification.service.EmailQueueService;
import com.ecm.notification.service.EmailTemplateService;
import com.ecm.notification.service.EmailTemplateService.RenderedEmail;
import com.ecm.notification.service.NotificationService;
import com.ecm.notification.service.PreferenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import jakarta.mail.internet.MimeMessage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Listens to events from ecm-admin and sends emails using DB-managed templates.
 *
 * Template resolution:
 *   1. Look up template by key from ecm_core.email_templates
 *   2. Substitute {{variable}} placeholders with event data
 *   3. If template not found/inactive → use hardcoded fallback (never silently skip)
 *
 * All emails are sent IMMEDIATELY (not batched) — they are time-sensitive
 * (OTP codes, invitations, status changes).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CaseEventListener {

    private final JavaMailSender mailSender;
    private final EmailTemplateService templateService;
    private final NotificationService notificationService;
    private final EmailQueueService emailQueueService;
    private final PreferenceService preferenceService;

    @Value("${ecm.notification.from-email:noreply@ecm.dev.local}")
    private String fromEmail;

    @Value("${ecm.notification.from-name:ECM Platform}")
    private String fromName;

    @Value("${ecm.notification.app-url:http://localhost:3000}")
    private String appUrl;

    // ── OTP Verification ────────────────────────────────────────────────────

    @RabbitListener(queues = "ecm.notification.case.otp")
    public void onOtpRequested(Map<String, Object> event) {
        String email = str(event.get("email"));
        String otp = str(event.get("otp"));

        if (email == null || otp == null) {
            log.warn("Invalid OTP event — missing email or otp: {}", event);
            return;
        }

        try {
            Map<String, Object> vars = Map.of("otp", otp);
            RenderedEmail rendered = templateService.render("OTP_VERIFICATION", vars);

            if (rendered != null) {
                sendImmediately(email, rendered.subject(), rendered.body());
            } else {
                // Fallback — template missing or inactive
                sendImmediately(email, "ECM — Your Verification Code",
                        "<div style='font-family:sans-serif;max-width:400px;margin:0 auto;padding:20px'>"
                        + "<h2 style='color:#1e40af'>Verification Code</h2>"
                        + "<p style='font-size:32px;font-weight:bold;letter-spacing:8px;color:#111;padding:16px 0;text-align:center;background:#f3f4f6;border-radius:8px'>"
                        + otp + "</p>"
                        + "<p style='color:#6b7280;font-size:14px'>This code expires in 10 minutes.</p>"
                        + "</div>");
            }
            log.info("OTP email sent to {}", email);
        } catch (Exception e) {
            log.error("Failed to send OTP email to {}: {}", email, e.getMessage());
        }
    }

    // ── External Participant Invitation ──────────────────────────────────────

    @RabbitListener(queues = "ecm.notification.case.invite")
    public void onParticipantAdded(Map<String, Object> event) {
        String email = str(event.get("email"));
        String name = str(event.get("name"));
        String role = str(event.get("role"));
        String inviteToken = str(event.get("inviteToken"));

        if (email == null || inviteToken == null) {
            log.warn("Invalid participant event — missing email or inviteToken: {}", event);
            return;
        }

        try {
            Map<String, Object> vars = new HashMap<>();
            vars.put("name", name != null ? name : "");
            vars.put("role", role != null ? role : "participant");
            vars.put("inviteLink", appUrl + "/external/case/" + inviteToken);
            vars.put("appUrl", appUrl);

            RenderedEmail rendered = templateService.render("PARTICIPANT_INVITE", vars);

            if (rendered != null) {
                sendImmediately(email, rendered.subject(), rendered.body());
            } else {
                sendImmediately(email, "ECM — You've been added to a case",
                        "<p>Hello " + name + ", you've been added as " + role + ". "
                        + "<a href='" + appUrl + "/external/case/" + inviteToken + "'>Access Case Portal</a></p>");
            }
            log.info("Invitation email sent to {}", email);
        } catch (Exception e) {
            log.error("Failed to send invitation email to {}: {}", email, e.getMessage());
        }
    }

    // ── User Platform Invitation ────────────────────────────────────────────

    @RabbitListener(queues = "ecm.notification.user.invited")
    public void onUserInvited(Map<String, Object> event) {
        String email = str(event.get("email"));
        String displayName = str(event.get("displayName"));
        String role = str(event.get("role"));

        if (email == null) {
            log.warn("Invalid user invite event — missing email: {}", event);
            return;
        }

        try {
            String roleName = role != null ? role.replace("ECM_", "").replace("_", " ") : "User";
            Map<String, Object> vars = new HashMap<>();
            vars.put("displayName", displayName != null ? displayName : "");
            vars.put("role", roleName);
            vars.put("signInLink", appUrl);
            vars.put("appUrl", appUrl);

            RenderedEmail rendered = templateService.render("USER_INVITE", vars);

            if (rendered != null) {
                sendImmediately(email, rendered.subject(), rendered.body());
            } else {
                sendImmediately(email, "ECM — You've been invited to the platform",
                        "<p>Hello " + displayName + ", you've been invited as " + roleName + ". "
                        + "<a href='" + appUrl + "'>Sign In</a></p>");
            }
            log.info("User invitation email sent to {}", email);
        } catch (Exception e) {
            log.error("Failed to send user invitation email to {}: {}", email, e.getMessage());
        }
    }

    // ── Case Assignment Notification ─────────────────────────────────────────

    @RabbitListener(queues = "ecm.notification.case.assigned")
    public void onCaseAssigned(Map<String, Object> event) {
        String caseId = str(event.get("caseId"));
        String caseRef = str(event.get("caseRef"));
        String customerName = str(event.get("customerName"));
        String assignedTo = str(event.get("assignedTo"));
        String assignedToGroup = str(event.get("assignedToGroup"));
        String assignedBy = str(event.get("assignedBy"));

        try {
            Map<String, String> emailVars = new HashMap<>();
            emailVars.put("caseRef", caseRef != null ? caseRef : "—");
            emailVars.put("customerName", customerName != null ? customerName : "—");
            emailVars.put("assignedBy", assignedBy != null ? assignedBy : "System");
            emailVars.put("caseId", caseId != null ? caseId : "");
            emailVars.put("appUrl", appUrl);

            if (assignedToGroup != null && !assignedToGroup.isBlank()) {
                String title = "Case assigned to your group";
                String body = "Case " + (caseRef != null ? caseRef : "") + " has been assigned to "
                        + assignedToGroup + " for review."
                        + (assignedBy != null ? " Assigned by: " + assignedBy : "");

                notificationService.notifyRole(assignedToGroup, title, body,
                        "/cases/" + caseId, "CASE_ASSIGNED");

                // Queue emails for all users in the group
                List<String> emails = notificationService.getUserEmailsForRole(assignedToGroup);
                for (String email : emails) {
                    if (preferenceService.isEnabled(email, "CASE_ASSIGNED", "EMAIL")) {
                        emailQueueService.queueFromTemplate(email, "CASE_ASSIGNED", emailVars);
                    }
                }

                log.info("Case assignment notification sent to group {}: caseId={}", assignedToGroup, caseId);
            } else if (assignedTo != null && !assignedTo.isBlank()) {
                String title = "Case assigned to you";
                String body = "Case " + (caseRef != null ? caseRef : "") + " has been assigned to you for review."
                        + (assignedBy != null ? " Assigned by: " + assignedBy : "");

                notificationService.notifyUser(assignedTo, title, body,
                        "/cases/" + caseId, "CASE_ASSIGNED");

                // Queue email for assignee
                if (preferenceService.isEnabled(assignedTo, "CASE_ASSIGNED", "EMAIL")) {
                    emailQueueService.queueFromTemplate(assignedTo, "CASE_ASSIGNED", emailVars);
                }

                log.info("Case assignment notification sent to {}: caseId={}", assignedTo, caseId);
            }
        } catch (Exception e) {
            log.warn("Failed to process case.assigned notification: {}", e.getMessage());
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void sendImmediately(String recipient, String subject, String body) throws Exception {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
        helper.setFrom(fromName + " <" + fromEmail + ">");
        helper.setTo(recipient);
        helper.setSubject(subject);
        helper.setText(body, true);
        mailSender.send(message);
    }

    private static String str(Object o) {
        return o != null ? o.toString().trim() : null;
    }
}
