package com.ecm.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Rate limiter policies using Spring Cloud Gateway's built-in Redis rate limiter.
 *
 * Algorithm: Token Bucket (sliding window approximation)
 *   - replenishRate:    tokens added per second (sustained throughput)
 *   - burstCapacity:    maximum tokens at any instant (burst allowance)
 *   - requestedTokens:  cost per request (always 1 here)
 *
 * IMPORTANT: Each method MUST be @Bean so Spring manages the RedisRateLimiter
 * instance and auto-injects the required ReactiveStringRedisTemplate dependency.
 * Plain "new RedisRateLimiter(...)" without @Bean leaves the internal
 * ReactiveStringRedisTemplate null, causing NullPointerException on first request.
 *
 * The rate limiter key is the JWT subject (user ID) from KeyResolverConfig —
 * each user gets their own token bucket. Unauthenticated requests are rejected
 * by the security filter before reaching the rate limiter.
 *
 * Redis keys: request_rate_limiter.{userId}.{tokens|timestamp}
 * These expire automatically after burstCapacity/replenishRate seconds.
 */
@Configuration
public class RateLimiterConfig {

    /**
     * Default policy: 20 requests/second sustained, burst up to 40.
     * Applied to: /api/auth/**, /api/users/**, /api/documents/**
     *
     * @Primary — required because GatewayAutoConfiguration's
     * requestRateLimiterGatewayFilterFactory needs exactly ONE RateLimiter bean
     * for its constructor. Without @Primary, having 3 RedisRateLimiter beans
     * causes "expected single bean but 3 found" at startup.
     * Routes that need uploadRateLimiter or adminRateLimiter inject them
     * directly via @Qualifier, which always takes precedence over @Primary.
     */
    @Primary
    @Bean(name = "defaultRateLimiter")
    public RedisRateLimiter defaultRateLimiter() {
        return new RedisRateLimiter(
                20,   // replenishRate: 20 tokens/second = 1200/minute sustained
                40,   // burstCapacity: up to 40 simultaneous requests
                1     // requestedTokens: each request costs 1 token
        );
    }

    /**
     * Upload policy: stricter — 2 uploads/second, burst of 5.
     * Applied to: POST /api/documents/upload
     */
    @Bean(name = "uploadRateLimiter")
    public RedisRateLimiter uploadRateLimiter() {
        return new RedisRateLimiter(
                2,    // 2 uploads/second
                5,    // burst of 5
                1
        );
    }

    /**
     * Admin policy: tighter for sensitive admin operations.
     * Applied to: /api/admin/** (when ecm-admin module is built)
     */
    @Bean(name = "adminRateLimiter")
    public RedisRateLimiter adminRateLimiter() {
        return new RedisRateLimiter(
                5,
                10,
                1
        );
    }
}