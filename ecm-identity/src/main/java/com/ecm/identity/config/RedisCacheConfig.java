package com.ecm.identity.config;

import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.time.Duration;

/**
 * Redis cache configuration for ecm-identity.
 *
 * Sprint G addition: explicit StringRedisTemplate bean used by EnrichmentService
 * for direct key/value operations (ecm:user:enrich:{sub} cache entries).
 * Spring Boot auto-configures a default StringRedisTemplate, but declaring it
 * explicitly ensures it is wired correctly alongside the caching infrastructure.
 */
@Configuration
@EnableCaching
public class RedisCacheConfig {

    /**
     * StringRedisTemplate for direct cache key operations.
     * Used by EnrichmentService to read/write/delete ecm:user:enrich:{sub} keys.
     */
    @Bean
    @Primary
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    @Bean
    public RedisCacheManagerBuilderCustomizer redisCacheManagerBuilderCustomizer() {
        return (builder) -> builder
                // User lookups: cache for 10 minutes
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
