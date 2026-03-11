package com.ecm.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Mono;

import java.security.Principal;

/**
 * KeyResolver for Spring Cloud Gateway rate limiting.
 *
 * WHY THIS IS REQUIRED:
 * The requestRateLimiter GatewayFilter requires exactly one KeyResolver bean
 * in the Spring context to determine the per-user bucket key. Without it,
 * the gateway will fail to start with:
 *   "No qualifying bean of type KeyResolver available"
 *
 * STRATEGY — JWT subject (Okta user ID):
 * We extract the authenticated principal name, which Spring sets to the
 * JWT "sub" claim via our JwtAuthenticationConverter in GatewaySecurityConfig.
 * This gives every user their own independent rate limit bucket, preventing
 * one heavy user from throttling everyone else.
 *
 * Fallback to "anonymous" is a safety net only — in practice, unauthenticated
 * requests are rejected by the security filter before reaching the rate limiter.
 */
@Configuration
public class KeyResolverConfig {

    /**
     * Rate limit key = authenticated user's principal name (Okta subject / sub claim).
     * Each user gets their own token bucket in Redis.
     *
     * Key format in Redis: request_rate_limiter.{jwtSubject}.tokens
     *                      request_rate_limiter.{jwtSubject}.timestamp
     */
    @Primary
    @Bean
    public KeyResolver userKeyResolver() {
        return exchange -> exchange.getPrincipal()
                .map(Principal::getName)
                .defaultIfEmpty("anonymous");
    }
}