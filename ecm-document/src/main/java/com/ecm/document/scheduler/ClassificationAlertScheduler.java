package com.ecm.document.scheduler;

import com.ecm.document.config.RabbitMqConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Scheduled check for documents stuck in an unclassified state.
 *
 * Runs every hour. If documents have been ACTIVE with no category_id
 * for longer than the configured threshold, publishes a
 * document.classification.stale event so ecm-notification can alert reviewers.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClassificationAlertScheduler {

    private final JdbcTemplate jdbc;
    private final RabbitTemplate rabbitTemplate;

    @Value("${ecm.classification.stale-threshold-hours:4}")
    private int staleThresholdHours;

    @Scheduled(fixedDelayString = "${ecm.classification.stale-check-interval-ms:3600000}")
    public void checkStaleClassifications() {
        try {
            // Count documents that are ACTIVE but have no category (needs classification)
            // and have been in that state for longer than the threshold
            var result = jdbc.queryForMap("""
                    SELECT COUNT(*) AS stale_count,
                           EXTRACT(EPOCH FROM (NOW() - MIN(created_at))) / 3600 AS oldest_hours
                    FROM ecm_core.documents
                    WHERE status = 'ACTIVE'
                      AND category_id IS NULL
                      AND created_at < NOW() - MAKE_INTERVAL(hours => ?)
                    """, staleThresholdHours);

            long staleCount = ((Number) result.get("stale_count")).longValue();

            if (staleCount == 0) {
                log.debug("No stale unclassified documents found (threshold={}h)", staleThresholdHours);
                return;
            }

            Double oldestHours = result.get("oldest_hours") != null
                    ? ((Number) result.get("oldest_hours")).doubleValue()
                    : null;
            String oldestAge = oldestHours != null
                    ? String.format("%.0f hours", oldestHours)
                    : staleThresholdHours + "+ hours";

            Map<String, Object> event = Map.of(
                    "count", staleCount,
                    "oldestAge", oldestAge,
                    "thresholdHours", staleThresholdHours
            );

            rabbitTemplate.convertAndSend(
                    RabbitMqConfig.EXCHANGE, "document.classification.stale", event);

            log.info("Published stale classification alert: {} documents unclassified for {}",
                    staleCount, oldestAge);
        } catch (Exception e) {
            log.warn("Stale classification check failed: {}", e.getMessage());
        }
    }
}
