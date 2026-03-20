package com.ecm.notification.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

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
}
