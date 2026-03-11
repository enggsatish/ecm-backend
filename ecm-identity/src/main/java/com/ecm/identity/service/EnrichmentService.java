package com.ecm.identity.service;

import com.ecm.identity.model.dto.EnrichmentResponseDto;
import com.ecm.identity.model.entity.Role;
import com.ecm.identity.model.entity.User;
import com.ecm.identity.repository.PermissionRepository;
import com.ecm.identity.repository.UserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Resolves roles and permissions for a given Okta subject (sub claim).
 *
 * Flow:
 *   1. Check Redis for cached enrichment data (15-minute TTL)
 *   2. If miss: query DB for user + roles + permissions
 *   3. Write result to Redis and return
 *
 * Called by InternalAuthController from ecm-gateway on cache miss.
 * Also called internally by IdentityService.buildSessionDto() for /api/auth/me.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EnrichmentService {

    private static final String  CACHE_KEY_PREFIX = "ecm:user:enrich:";
    private static final Duration CACHE_TTL        = Duration.ofMinutes(15);

    private final UserRepository       userRepository;
    private final PermissionRepository permissionRepository;
    private final StringRedisTemplate  redis;
    private final ObjectMapper         objectMapper;

    // ── Primary enrichment entry point ─────────────────────────────────────

    /**
     * Resolves roles and permissions for a given JWT subject + email.
     *
     * Three user states handled:
     *
     *   1. ACTIVE, known sub     → normal enrichment (cache hit or DB lookup)
     *   2. PENDING (invited)     → entra_object_id IS NULL, is_active=false, email matches
     *                              → auto-activate, bind sub, return their pre-assigned roles
     *   3. DEACTIVATED           → entra_object_id IS NOT NULL, is_active=false
     *                              → return NO_ACCESS (admin intentionally disabled)
     *   4. Unknown               → never been invited or logged in → return NO_ACCESS
     *
     * NOT readOnly: the pending-user activation path writes to the DB.
     */
    @Transactional
    public EnrichmentResponseDto enrich(String sub, String email) {
        String cacheKey = CACHE_KEY_PREFIX + sub;

        // 1. Redis cache check
        String cached = redis.opsForValue().get(cacheKey);
        if (cached != null) {
            try {
                log.debug("Enrichment cache HIT for sub={}", sub);
                return objectMapper.readValue(cached, EnrichmentResponseDto.class);
            } catch (JsonProcessingException e) {
                log.warn("Failed to deserialize cached enrichment for sub={}, fetching fresh", sub);
            }
        }

        // 2. DB lookup by sub — covers active users on all subsequent logins
        Optional<User> userOpt = userRepository.findByEntraObjectIdAndIsActiveTrue(sub);

        if (userOpt.isEmpty() && email != null) {
            // 3. Sub not found (or inactive) — check for an admin-invited pending user.
            //    Pending users: entra_object_id IS NULL, is_active = false.
            //    Deactivated users: entra_object_id IS NOT NULL, is_active = false.
            //    We only auto-activate the pending case.
            log.debug("Enrichment cache HIT for sub={}", sub);
            Optional<User> pendingOpt = userRepository.findPendingByEmailWithRoles(email);

            if (pendingOpt.isPresent()) {
                User pending = pendingOpt.get();
                log.info("Auto-activating invited user on first login: email={}, userId={}",
                        email, pending.getId());

                // Bind the SSO subject and activate the account
                pending.setEntraObjectId(sub);
                pending.setIsActive(true);
                pending.setLastLogin(OffsetDateTime.now());
                userRepository.save(pending);

                // Reload so the save is reflected in the entity we use below
                log.debug("before findByEntraObjectIdAndIsActiveTrue : sub : " + sub);
                userOpt = userRepository.findByEntraObjectIdAndIsActiveTrue(sub);
            }
        }

        if (userOpt.isEmpty()) {
            log.info("Enrichment: no active user for sub={} email={}, returning NO_ACCESS", sub, email);
            return EnrichmentResponseDto.noAccess();
        }

        User user = userOpt.get();

        // 4. Fetch roles
        Set<String> roles = user.getRoles().stream()
                .map(Role::getName)
                .collect(Collectors.toSet());

        if (roles.isEmpty()) {
            log.info("Enrichment: user {} has no roles assigned, returning NO_ACCESS", email);
            return EnrichmentResponseDto.noAccess();
        }

        // 5. Fetch permissions — UNION across all user roles
        List<Integer> roleIds = user.getRoles().stream()
                .map(Role::getId)
                .collect(Collectors.toList());
        Set<String> permissions = permissionRepository.findCodesByRoleIds(roleIds);

        // 6. Build response
        EnrichmentResponseDto dto = EnrichmentResponseDto.builder()
                .status("OK")
                .userId(user.getId())
                .roles(new ArrayList<>(roles))
                .permissions(new ArrayList<>(permissions))
                .cachedAt(OffsetDateTime.now().toString())
                .build();

        // 7. Cache the result
        try {
            redis.opsForValue().set(cacheKey, objectMapper.writeValueAsString(dto), CACHE_TTL);
            log.debug("Enrichment cached for sub={}, roles={}", sub, roles);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize enrichment result for caching: {}", e.getMessage());
        }

        return dto;
    }

    // ── Cache invalidation helpers ─────────────────────────────────────────

    /**
     * Invalidates a single user's enrichment cache entry.
     * Call after role add/remove or user deactivation.
     */
    public void invalidateCache(String sub) {
        String key = CACHE_KEY_PREFIX + sub;
        redis.delete(key);
        log.debug("Invalidated enrichment cache for sub={}", sub);
    }

    /**
     * Looks up the sub for a userId and invalidates their cache entry.
     */
    public void invalidateCacheForUserId(Integer userId) {
        userRepository.findById(userId).ifPresent(u -> {
            if (u.getEntraObjectId() != null) {
                invalidateCache(u.getEntraObjectId());
                log.debug("Invalidated enrichment cache for userId={}", userId);
            }
        });
    }
}