package com.ecm.notification.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Queues emails for batch delivery.
 * Emails are stored in ecm_core.email_queue with status=PENDING.
 * The EmailBatchProcessor picks them up every 5 minutes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailQueueService {

    private final JdbcTemplate jdbc;

    /**
     * Queue an email with template rendering.
     * Looks up the template by key, renders with variables, stores in queue.
     */
    @Transactional
    public void queueFromTemplate(String recipient, String templateKey, Map<String, String> variables) {
        try {
            var templates = jdbc.queryForList(
                    "SELECT subject_template, body_template FROM ecm_core.email_templates WHERE template_key = ? AND is_active = true",
                    templateKey);

            if (templates.isEmpty()) {
                log.warn("No active email template found for key={}, skipping email for {}", templateKey, recipient);
                return;
            }

            String subjectTemplate = (String) templates.get(0).get("subject_template");
            String bodyTemplate = (String) templates.get(0).get("body_template");

            // Render templates with simple {{variable}} replacement
            String subject = render(subjectTemplate, variables);
            String body = render(bodyTemplate, variables);

            jdbc.update("""
                INSERT INTO ecm_core.email_queue (recipient, subject, body, category, status)
                VALUES (?, ?, ?, ?, 'PENDING')
                """, recipient, subject, body, templateKey);

            log.debug("Email queued: to={}, template={}", recipient, templateKey);
        } catch (Exception e) {
            log.error("Failed to queue email: to={}, template={}: {}", recipient, templateKey, e.getMessage());
        }
    }

    /**
     * Queue a pre-rendered email (for digest or custom content).
     */
    @Transactional
    public void queueDirect(String recipient, String subject, String body, String category) {
        jdbc.update("""
            INSERT INTO ecm_core.email_queue (recipient, subject, body, category, status)
            VALUES (?, ?, ?, ?, 'PENDING')
            """, recipient, subject, body, category);
    }

    /**
     * Simple {{variable}} template renderer.
     * Replaces {{key}} with the corresponding value from the variables map.
     */
    private String render(String template, Map<String, String> variables) {
        String result = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}", entry.getValue() != null ? entry.getValue() : "");
        }
        // Remove any unreplaced {{variables}}
        result = result.replaceAll("\\{\\{[^}]+\\}\\}", "");
        return result;
    }
}
