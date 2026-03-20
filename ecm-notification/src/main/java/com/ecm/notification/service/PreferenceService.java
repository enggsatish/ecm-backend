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
public class PreferenceService {

    private final JdbcTemplate jdbc;

    public record PreferenceDto(String category, String channel, Boolean enabled) {}

    @Transactional(readOnly = true)
    public List<PreferenceDto> getForUser(String email) {
        return jdbc.query("""
            SELECT category, channel, enabled FROM ecm_core.notification_preferences
            WHERE user_email = ? ORDER BY category, channel
            """, (rs, rowNum) -> new PreferenceDto(
                rs.getString("category"), rs.getString("channel"), rs.getBoolean("enabled")
        ), email);
    }

    @Transactional
    public void setPreference(String email, String category, String channel, boolean enabled) {
        jdbc.update("""
            INSERT INTO ecm_core.notification_preferences (user_email, category, channel, enabled, updated_at)
            VALUES (?, ?, ?, ?, NOW())
            ON CONFLICT (user_email, category, channel)
            DO UPDATE SET enabled = ?, updated_at = NOW()
            """, email, category, channel, enabled, enabled);
    }

    /** Check if a user has opted out of a specific notification */
    @Transactional(readOnly = true)
    public boolean isEnabled(String email, String category, String channel) {
        var results = jdbc.queryForList("""
            SELECT enabled FROM ecm_core.notification_preferences
            WHERE user_email = ? AND category = ? AND channel = ?
            """, email, category, channel);
        // Default: enabled (if no preference record exists)
        if (results.isEmpty()) return true;
        return Boolean.TRUE.equals(results.get(0).get("enabled"));
    }
}
