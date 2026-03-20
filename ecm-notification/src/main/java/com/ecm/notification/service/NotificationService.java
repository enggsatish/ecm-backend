package com.ecm.notification.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * In-app notification service.
 * Notifications are stored in ecm_core.notifications via JdbcTemplate.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final JdbcTemplate jdbc;

    // ── DTOs ─────────────────────────────────────────────────────────────────

    public record NotificationDto(
            Long id, String title, String body, String link,
            String category, Boolean isRead, OffsetDateTime createdAt
    ) {}

    // ── Read ─────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<NotificationDto> getForUser(String recipient, boolean unreadOnly) {
        String sql = unreadOnly
                ? "SELECT id, title, body, link, category, is_read, created_at FROM ecm_core.notifications WHERE recipient = ? AND is_read = false ORDER BY created_at DESC LIMIT 50"
                : "SELECT id, title, body, link, category, is_read, created_at FROM ecm_core.notifications WHERE recipient = ? ORDER BY created_at DESC LIMIT 50";

        return jdbc.query(sql, (rs, rowNum) -> new NotificationDto(
                rs.getLong("id"), rs.getString("title"), rs.getString("body"),
                rs.getString("link"), rs.getString("category"),
                rs.getBoolean("is_read"), rs.getObject("created_at", OffsetDateTime.class)
        ), recipient);
    }

    @Transactional(readOnly = true)
    public int getUnreadCount(String recipient) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM ecm_core.notifications WHERE recipient = ? AND is_read = false",
                Integer.class, recipient);
        return count != null ? count : 0;
    }

    // ── Write ────────────────────────────────────────────────────────────────

    @Transactional
    public void create(String recipient, String title, String body, String link, String category) {
        jdbc.update("""
            INSERT INTO ecm_core.notifications (recipient, title, body, link, category)
            VALUES (?, ?, ?, ?, ?)
            """, recipient, title, body, link, category != null ? category : "GENERAL");
        log.debug("Notification created for {}: {}", recipient, title);
    }

    /** Send notification to all active users with a specific role */
    @Transactional
    public void notifyRole(String roleName, String title, String body, String link, String category) {
        List<String> recipients = jdbc.queryForList("""
            SELECT u.email FROM ecm_core.users u
            JOIN ecm_core.user_roles ur ON ur.user_id = u.id
            JOIN ecm_core.roles r ON r.id = ur.role_id
            WHERE r.name = ? AND u.is_active = true
            """, String.class, roleName);

        for (String email : recipients) {
            create(email, title, body, link, category);
        }
        log.info("Notification sent to {} users with role {}: {}", recipients.size(), roleName, title);
    }

    /** Send notification to a specific user by email */
    @Transactional
    public void notifyUser(String email, String title, String body, String link, String category) {
        create(email, title, body, link, category);
    }

    // ── Lookup helpers (cross-schema reads) ─────────────────────────────────

    @Transactional(readOnly = true)
    public List<String> getUserEmailsForRole(String roleName) {
        return jdbc.queryForList("""
            SELECT u.email FROM ecm_core.users u
            JOIN ecm_core.user_roles ur ON ur.user_id = u.id
            JOIN ecm_core.roles r ON r.id = ur.role_id
            WHERE r.name = ? AND u.is_active = true
            """, String.class, roleName);
    }

    @Transactional(readOnly = true)
    public String getSubmitterEmail(String submissionId) {
        try {
            return jdbc.queryForObject(
                    "SELECT submitted_by FROM ecm_forms.form_submissions WHERE id = ?::uuid",
                    String.class, submissionId);
        } catch (Exception e) {
            log.debug("Could not find submitter for submission {}: {}", submissionId, e.getMessage());
            return null;
        }
    }

    // ── Mark read ────────────────────────────────────────────────────────────

    @Transactional
    public void markRead(Long id, String recipient) {
        jdbc.update("UPDATE ecm_core.notifications SET is_read = true WHERE id = ? AND recipient = ?",
                id, recipient);
    }

    @Transactional
    public void markAllRead(String recipient) {
        int count = jdbc.update(
                "UPDATE ecm_core.notifications SET is_read = true WHERE recipient = ? AND is_read = false",
                recipient);
        log.debug("Marked {} notifications as read for {}", count, recipient);
    }
}
