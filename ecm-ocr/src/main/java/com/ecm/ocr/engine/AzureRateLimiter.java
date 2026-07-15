package com.ecm.ocr.engine;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Simple rate limiter for Azure Document Intelligence API calls.
 *
 * <p>Reads {@code azure.rate_limit_per_second} from {@code ecm_admin.tenant_config}.
 * Default: 1 call/sec (safe for dev/S0 tier). Production can be set higher via Admin UI.</p>
 *
 * <p>Thread-safe — multiple OCR consumers can call {@link #acquire()} concurrently.
 * Uses CAS-based spacing to ensure minimum interval between calls.</p>
 */
@Component
@Slf4j
public class AzureRateLimiter {

    private final JdbcTemplate jdbc;
    private volatile long minIntervalNanos;
    private final AtomicLong lastCallNanos = new AtomicLong(0);

    private static final String CONFIG_KEY = "azure.rate_limit_per_second";
    private static final int DEFAULT_RATE = 1;

    public AzureRateLimiter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.minIntervalNanos = 1_000_000_000L / DEFAULT_RATE;
        refreshRate();
    }

    /**
     * Block until a permit is available. Call before every Azure API request.
     * Returns immediately if rate limiting is disabled (rate = 0).
     */
    public void acquire() {
        long interval = minIntervalNanos;
        if (interval <= 0) return; // unlimited

        for (int spin = 0; spin < 1000; spin++) {
            long now = System.nanoTime();
            long last = lastCallNanos.get();
            long nextAllowed = last + interval;

            if (now >= nextAllowed) {
                if (lastCallNanos.compareAndSet(last, now)) {
                    return; // permit acquired
                }
                // CAS failed — another thread got it, retry
                continue;
            }

            // Need to wait
            long sleepMs = (nextAllowed - now) / 1_000_000;
            if (sleepMs > 0) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        // Fallback: proceed anyway after excessive spinning (shouldn't happen)
        log.warn("Azure rate limiter spin limit reached — proceeding");
    }

    /**
     * Reload rate from DB every 60 seconds. Allows admin to adjust without restart.
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void refreshRate() {
        try {
            String value = jdbc.queryForObject(
                    "SELECT value FROM ecm_admin.tenant_config WHERE key = ?",
                    String.class, CONFIG_KEY);
            if (value != null && !value.isBlank()) {
                int rate = Integer.parseInt(value.trim());
                minIntervalNanos = rate > 0 ? 1_000_000_000L / rate : 0;
                log.debug("Azure rate limit refreshed: {} calls/sec", rate);
                return;
            }
        } catch (Exception e) {
            // No config row or parse error — use default
        }
        minIntervalNanos = 1_000_000_000L / DEFAULT_RATE;
    }
}
