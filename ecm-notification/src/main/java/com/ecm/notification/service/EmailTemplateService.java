package com.ecm.notification.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailTemplateService {

    private final JdbcTemplate jdbc;

    public record EmailTemplateDto(
            Integer id, String templateKey, String name,
            String subjectTemplate, String bodyTemplate,
            Boolean isActive, OffsetDateTime updatedAt
    ) {}

    public record UpdateTemplateRequest(
            String name, String subjectTemplate, String bodyTemplate, Boolean isActive
    ) {}

    @Transactional(readOnly = true)
    public List<EmailTemplateDto> listAll() {
        return jdbc.query("""
            SELECT id, template_key, name, subject_template, body_template, is_active, updated_at
            FROM ecm_core.email_templates ORDER BY template_key
            """, (rs, rowNum) -> new EmailTemplateDto(
                rs.getInt("id"), rs.getString("template_key"), rs.getString("name"),
                rs.getString("subject_template"), rs.getString("body_template"),
                rs.getBoolean("is_active"), rs.getObject("updated_at", OffsetDateTime.class)
        ));
    }

    @Transactional(readOnly = true)
    public EmailTemplateDto getById(Integer id) {
        var list = jdbc.query("""
            SELECT id, template_key, name, subject_template, body_template, is_active, updated_at
            FROM ecm_core.email_templates WHERE id = ?
            """, (rs, rowNum) -> new EmailTemplateDto(
                rs.getInt("id"), rs.getString("template_key"), rs.getString("name"),
                rs.getString("subject_template"), rs.getString("body_template"),
                rs.getBoolean("is_active"), rs.getObject("updated_at", OffsetDateTime.class)
        ), id);
        return list.isEmpty() ? null : list.get(0);
    }

    @Transactional
    public void update(Integer id, UpdateTemplateRequest req) {
        jdbc.update("""
            UPDATE ecm_core.email_templates
            SET name = COALESCE(?, name),
                subject_template = COALESCE(?, subject_template),
                body_template = COALESCE(?, body_template),
                is_active = COALESCE(?, is_active),
                updated_at = NOW()
            WHERE id = ?
            """, req.name(), req.subjectTemplate(), req.bodyTemplate(), req.isActive(), id);
        log.info("Email template updated: id={}", id);
    }

    @Transactional
    public void create(String templateKey, String name, String subjectTemplate, String bodyTemplate) {
        jdbc.update("""
            INSERT INTO ecm_core.email_templates (template_key, name, subject_template, body_template)
            VALUES (?, ?, ?, ?)
            """, templateKey, name, subjectTemplate, bodyTemplate);
        log.info("Email template created: key={}", templateKey);
    }

    // ── Template resolution + variable substitution ──────────────────────

    public record RenderedEmail(String subject, String body) {}

    /**
     * Resolves a template by key and substitutes {{variable}} placeholders.
     * Returns null if template not found or inactive.
     */
    @Transactional(readOnly = true)
    public RenderedEmail render(String templateKey, Map<String, Object> variables) {
        var list = jdbc.query("""
            SELECT subject_template, body_template
            FROM ecm_core.email_templates
            WHERE template_key = ? AND is_active = true
            """, (rs, rowNum) -> new String[]{
                rs.getString("subject_template"),
                rs.getString("body_template")
        }, templateKey);

        if (list.isEmpty()) {
            log.warn("Email template not found or inactive: {}", templateKey);
            return null;
        }

        String subject = substituteVariables(list.get(0)[0], variables);
        String body = substituteVariables(list.get(0)[1], variables);
        return new RenderedEmail(subject, body);
    }

    /**
     * Replace {{variableName}} placeholders with values from the map.
     * Unmatched placeholders are left as-is (visible in email = easy to debug).
     */
    private String substituteVariables(String template, Map<String, Object> variables) {
        if (template == null || variables == null) return template;
        String result = template;
        for (Map.Entry<String, Object> entry : variables.entrySet()) {
            String placeholder = "{{" + entry.getKey() + "}}";
            String value = entry.getValue() != null ? entry.getValue().toString() : "";
            result = result.replace(placeholder, value);
        }
        return result;
    }
}
