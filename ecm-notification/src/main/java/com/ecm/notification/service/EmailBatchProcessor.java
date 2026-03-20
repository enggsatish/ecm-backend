package com.ecm.notification.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.mail.internet.MimeMessage;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Batch email processor — runs every 5 minutes.
 *
 * Groups PENDING emails by recipient into a single digest email.
 * Uses HTML templates from ecm_core.email_templates.
 *
 * Dev: sends to Mailpit (localhost:1025) — view at http://localhost:8025
 * Prod: sends via configured SMTP.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailBatchProcessor {

    private final JdbcTemplate jdbc;
    private final JavaMailSender mailSender;

    @Value("${ecm.notification.from-email:noreply@ecm.dev.local}")
    private String fromEmail;

    @Value("${ecm.notification.from-name:ECM Platform}")
    private String fromName;

    @Value("${ecm.notification.email-enabled:true}")
    private boolean emailEnabled;

    @Value("${ecm.notification.app-url:http://localhost:3000}")
    private String appUrl;

    /**
     * Runs every 5 minutes. Picks up PENDING emails, groups by recipient,
     * sends one digest email per recipient, marks as SENT.
     */
    @Scheduled(fixedDelayString = "${ecm.notification.batch-interval-ms:300000}")
    public void processBatch() {
        if (!emailEnabled) return;

        List<Map<String, Object>> pending = jdbc.queryForList("""
            SELECT id, recipient, subject, body, category, created_at
            FROM ecm_core.email_queue
            WHERE status = 'PENDING'
            ORDER BY created_at ASC
            LIMIT 500
            """);

        if (pending.isEmpty()) return;

        log.info("Processing email batch: {} pending emails", pending.size());

        // Group by recipient
        Map<String, List<Map<String, Object>>> byRecipient = pending.stream()
                .collect(Collectors.groupingBy(r -> (String) r.get("recipient")));

        for (Map.Entry<String, List<Map<String, Object>>> entry : byRecipient.entrySet()) {
            String recipient = entry.getKey();
            List<Map<String, Object>> emails = entry.getValue();

            try {
                if (emails.size() == 1) {
                    // Single email — send directly
                    Map<String, Object> email = emails.get(0);
                    sendHtml(recipient, (String) email.get("subject"), (String) email.get("body"));
                } else {
                    // Multiple emails — send as digest
                    sendDigest(recipient, emails);
                }

                // Mark all as SENT
                List<Long> ids = emails.stream()
                        .map(e -> ((Number) e.get("id")).longValue())
                        .toList();
                String placeholders = ids.stream().map(id -> "?").collect(Collectors.joining(","));
                jdbc.update(
                        "UPDATE ecm_core.email_queue SET status = 'SENT', sent_at = NOW() WHERE id IN (" + placeholders + ")",
                        ids.toArray());

                log.info("Sent {} email(s) to {}", emails.size(), recipient);

            } catch (Exception e) {
                log.error("Failed to send email batch to {}: {}", recipient, e.getMessage());
                // Mark as FAILED
                List<Long> ids = emails.stream()
                        .map(em -> ((Number) em.get("id")).longValue())
                        .toList();
                String placeholders = ids.stream().map(id -> "?").collect(Collectors.joining(","));
                List<Object> params = new ArrayList<>(List.of(e.getMessage()));
                params.addAll(ids);
                jdbc.update(
                        "UPDATE ecm_core.email_queue SET status = 'FAILED', error_message = ? WHERE id IN (" +
                                placeholders + ")",
                        params.toArray());
            }
        }
    }

    private void sendDigest(String recipient, List<Map<String, Object>> emails) throws Exception {
        // Build digest HTML
        StringBuilder items = new StringBuilder();
        for (Map<String, Object> email : emails) {
            String subject = (String) email.get("subject");
            items.append("<div style=\"padding:8px 12px;border-left:3px solid #2563eb;margin-bottom:8px;background:#f9fafb;border-radius:0 4px 4px 0\">");
            items.append("<p style=\"margin:0;font-size:13px;color:#374151\">").append(subject != null ? subject : "Notification").append("</p>");
            items.append("</div>");
        }

        // Load digest template
        var templates = jdbc.queryForList(
                "SELECT subject_template, body_template FROM ecm_core.email_templates WHERE template_key = 'DIGEST' AND is_active = true");

        String subject;
        String body;

        if (!templates.isEmpty()) {
            subject = ((String) templates.get(0).get("subject_template"))
                    .replace("{{count}}", String.valueOf(emails.size()));
            body = ((String) templates.get(0).get("body_template"))
                    .replace("{{count}}", String.valueOf(emails.size()))
                    .replace("{{items}}", items.toString())
                    .replace("{{appUrl}}", appUrl);
        } else {
            subject = "[ECM] You have " + emails.size() + " new notifications";
            body = "<p>You have " + emails.size() + " new notifications. Login to ECM to view them.</p>";
        }

        sendHtml(recipient, subject, body);
    }

    private void sendHtml(String recipient, String subject, String body) throws Exception {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
        helper.setFrom(fromName + " <" + fromEmail + ">");
        helper.setTo(recipient);
        helper.setSubject(subject != null ? subject : "[ECM] Notification");
        helper.setText(body != null ? body : "", true); // true = HTML

        mailSender.send(message);
    }
}
