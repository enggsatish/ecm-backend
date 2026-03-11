package com.ecm.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Rate limiter configuration for ECM Gateway.
 *
 * Defines TWO RedisRateLimiter beans matching the @Qualifier names
 * that RouteConfig injects:
 *   - "defaultRateLimiter"  @Primary → general API endpoints (20 req/s, burst 40)
 *   - "uploadRateLimiter"            → document upload endpoint (5 req/s, burst 10)
 *
 * NOTE: KeyResolver is defined separately in KeyResolverConfig — it is NOT
 * defined here. Defining it in both classes caused:
 *   "expected single matching bean but found 2: userKeyResolver, principalNameKeyResolver"
 *
 * WHY @Primary on defaultRateLimiter:
 *   GatewayAutoConfiguration's requestRateLimiterGatewayFilterFactory injects
 *   RateLimiter<?> without a @Qualifier. With two beans of the same type,
 *   Spring cannot choose and fails with "expected single matching bean but found 2".
 *   @Primary designates defaultRateLimiter for all unqualified injection points.
 *   RouteConfig injects both beans by explicit @Qualifier, unaffected by @Primary.
 *
 * WHY includeHeaders = false on both:
 *   Spring WebFlux locks response headers (ReadOnlyHttpHeaders) the moment
 *   the downstream service begins writing its response body. RedisRateLimiter
 *   writes X-RateLimit-* headers in an async callback that fires AFTER the
 *   response has already committed — throwing UnsupportedOperationException
 *   and causing Netty to drop the connection mid-stream (ERR_INCOMPLETE_CHUNKED_ENCODING).
 *
 *   includeHeaders = false disables ONLY the response header writing.
 *   Rate limiting enforcement (allow/deny via Redis token bucket) is completely
 *   unaffected — requests are still throttled and 429 is returned when exhausted.
 *
 * Observability without X-RateLimit-* response headers:
 *   - 429 responses appear in RequestLoggingFilter access logs
 *   - Actuator: GET /actuator/metrics/spring.cloud.gateway.requests
 *   - Redis keys: request_rate_limiter.{key}.tokens / .timestamp
 */
@Configuration
public class RateLimiterConfig {

    /**
     * Default rate limiter — applied to all general API routes.
     *
     * @Primary required so GatewayAutoConfiguration's unqualified
     * RateLimiter<?> injection point resolves without ambiguity.
     *
     * replenishRate   = 20  → steady-state requests per second per user
     * burstCapacity   = 40  → token bucket ceiling (allows short bursts)
     * requestedTokens = 1   → each request consumes 1 token
     */
    @Primary
    @Bean("defaultRateLimiter")
    public RedisRateLimiter defaultRateLimiter() {
        RedisRateLimiter limiter = new RedisRateLimiter(20, 40, 1);
        limiter.setIncludeHeaders(false);
        return limiter;
    }

    /**
     * Upload rate limiter — applied to POST /api/documents/upload only.
     *
     * Tighter limits because uploads are expensive: MinIO write, OCR queue
     * publish to RabbitMQ, workflow trigger. A user uploading at 5 docs/sec
     * is almost certainly a script, not a human operator.
     *
     * replenishRate   = 5   → 5 uploads per second per user
     * burstCapacity   = 10  → allows a quick burst of 10 before throttling
     * requestedTokens = 1
     */
    @Bean("uploadRateLimiter")
    public RedisRateLimiter uploadRateLimiter() {
        RedisRateLimiter limiter = new RedisRateLimiter(5, 10, 1);
        limiter.setIncludeHeaders(false);
        return limiter;
    }
}