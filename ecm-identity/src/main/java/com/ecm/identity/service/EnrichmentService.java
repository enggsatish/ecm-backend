package com.ecm.identity.service;

import com.ecm.identity.model.dto.EnrichmentRequestDto;
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
 *
 * ── No auto-provisioning ────────────────────────────────────────────────────
 * Users are NOT auto-created on login. SUPER_ADMIN must add users via Admin UI.
 * SUPER_ADMIN role is seeded via SQL after first Okta login creates the user
 * record in IdentityService.syncUserFromToken().
 *
 * Flow for first-run:
 *   1. Run init.sql (seeds roles, permissions, config)
 *   2. Login via Okta → syncUserFromToken creates user with NO roles
 *   3. Run SQL: INSERT INTO ecm_core.user_roles ... (assign SUPER_ADMIN)
 *   4. SUPER_ADMIN can now add other users via Admin UI
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EnrichmentService {

    private static final String   CACHE_KEY_PREFIX = "ecm:user:enrich:";
    private static final Duration CACHE_TTL        = Duration.ofMinutes(15);

    private final UserRepository       userRepository;
    private final PermissionRepository permissionRepository;
    private final StringRedisTemplate  redis;
    private final ObjectMapper         objectMapper;

    // ── Primary enrichment entry point ──────────────────────────────────────

    /**
     * Resolves roles and permissions for a given JWT subject + email.
     *
     * User state machine:
     *
     *   1. ACTIVE, known sub     → normal enrichment (cache hit or DB lookup)
     *   2. PENDING (invited)     → entra_object_id = 'PENDING', is_active=false, email matches
     *                              → auto-activate, bind sub, return pre-assigned roles
     *   3. DEACTIVATED           → entra_object_id IS NOT NULL, is_active=false
     *                              → return NO_ACCESS (admin intentionally disabled)
     *   4. Unknown + ECM_ADMIN   → not in DB but has ECM_ADMIN Okta group
     *                              → BOOTSTRAP: auto-provision with ECM_ADMIN role (first-run only)
     *   5. Unknown (no admin)    → no record, no admin group → return NO_ACCESS
     *
     * NOT readOnly: pending activation and bootstrap paths write to the DB.
     */
    @Transactional
    public EnrichmentResponseDto enrich(String sub, String email, List<String> oktaGroups) {
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
            // 3. Sub not found — check for an admin-invited user (entra_object_id IS NULL).
            //    On first login: bind real SSO subject (Okta user ID / Entra Object ID),
            //    keep active, return pre-assigned roles.
            log.debug("No active user for sub={} — checking for pending invitation by email={}", sub, email);
            Optional<User> pendingOpt = userRepository.findPendingByEmailWithRoles(email);

            if (pendingOpt.isPresent()) {
                User pending = pendingOpt.get();
                log.info("Auto-activating invited user on first login: email={}, userId={}",
                        email, pending.getId());

                pending.setEntraObjectId(sub);
                pending.setIsActive(true);
                pending.setLastLogin(OffsetDateTime.now());
                userRepository.save(pending);

                userOpt = userRepository.findByEntraObjectIdAndIsActiveTrue(sub);
            }
        }

        // Unknown user — create a bare record (no roles) so SUPER_ADMIN can see
        // them in the Users list and assign roles. Returns NO_ACCESS.
        if (userOpt.isEmpty()) {
            log.info("Unknown user first contact: sub={}, email={}. " +
                    "Creating user record with no roles. SUPER_ADMIN must assign access.", sub, email);

            User newUser = new User();
            newUser.setEntraObjectId(sub);
            newUser.setEmail(email != null ? email : sub);
            newUser.setDisplayName(email != null ? email.split("@")[0] : sub);
            newUser.setIsActive(true);
            newUser.setLastLogin(OffsetDateTime.now());
            newUser.setRoles(new HashSet<>());
            userRepository.save(newUser);

            return EnrichmentResponseDto.noAccess();
        }

        User user = userOpt.get();

        // 5. Fetch roles
        Set<String> roles = user.getRoles().stream()
                .map(Role::getName)
                .collect(Collectors.toSet());

        if (roles.isEmpty()) {
            log.info("Enrichment: user {} has no roles assigned, returning NO_ACCESS", email);
            return EnrichmentResponseDto.noAccess();
        }

        // 6. Fetch permissions — UNION across all user roles
        List<Integer> roleIds = user.getRoles().stream()
                .map(Role::getId)
                .collect(Collectors.toList());
        Set<String> permissions = permissionRepository.findCodesByRoleIds(roleIds);

        // 7. Build response
        EnrichmentResponseDto dto = EnrichmentResponseDto.builder()
                .status("OK")
                .userId(user.getId())
                .roles(new ArrayList<>(roles))
                .permissions(new ArrayList<>(permissions))
                .cachedAt(OffsetDateTime.now().toString())
                .build();

        // 8. Cache the result
        try {
            redis.opsForValue().set(cacheKey, objectMapper.writeValueAsString(dto), CACHE_TTL);
            log.debug("Enrichment cached for sub={}, roles={}", sub, roles);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize enrichment result for caching: {}", e.getMessage());
        }

        return dto;
    }

    /**
     * Legacy overload for callers that don't yet pass oktaGroups.
     * Routes to the full method with an empty groups list (no bootstrap).
     */
    @Transactional
    public EnrichmentResponseDto enrich(String sub, String email) {
        return enrich(sub, email, Collections.emptyList());
    }

    // ── Cache invalidation helpers ───────────────────────────────────────────

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