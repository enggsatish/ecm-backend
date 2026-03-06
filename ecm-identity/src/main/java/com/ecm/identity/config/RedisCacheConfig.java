package com.ecm.identity.config;

import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.time.Duration;

/**
 * Redis-backed cache configuration for ecm-identity.
 *
 * WHY THIS IS SEPARATE FROM EcmIdentityApplication:
 * @EnableCaching was previously on the main class without any CacheManager bean.
 * Spring silently fell back to ConcurrentMapCache (in-memory, not shared between
 * instances, cleared on restart). This is production-incorrect behaviour.
 *
 * This class enables caching properly with Redis as the store. Cached data
 * is shared across all identity service instances and survives restarts.
 *
 * Cache regions:
 *   "users"    — User entity lookups by entraObjectId, TTL 10 minutes
 *   "sessions" — UserSessionDto, TTL 5 minutes (aligned with frontend staleTime)
 */
@Configuration
@EnableCaching
public class RedisCacheConfig {

    @Bean
    public RedisCacheManagerBuilderCustomizer redisCacheManagerBuilderCustomizer() {
        return (builder) -> builder
                // User lookups: cache for 10 minutes
                // Invalidated on user deactivation (call cache.evict in IdentityService)
                .withCacheConfiguration("users",
                        RedisCacheConfiguration.defaultCacheConfig()
                                .entryTtl(Duration.ofMinutes(10))
                                .serializeValuesWith(
                                        RedisSerializationContext.SerializationPair
                                                .fromSerializer(new GenericJackson2JsonRedisSerializer())))
                // Session DTOs: cache for 5 minutes
                .withCacheConfiguration("sessions",
                        RedisCacheConfiguration.defaultCacheConfig()
                                .entryTtl(Duration.ofMinutes(5))
                                .serializeValuesWith(
                                        RedisSerializationContext.SerializationPair
                                                .fromSerializer(new GenericJackson2JsonRedisSerializer())));
    }
}