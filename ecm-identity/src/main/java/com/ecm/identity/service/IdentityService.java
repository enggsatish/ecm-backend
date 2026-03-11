package com.ecm.identity.service;

import com.ecm.common.exception.ResourceNotFoundException;
import com.ecm.identity.model.dto.UserProfileDto;
import com.ecm.identity.model.dto.UserSessionDto;
import com.ecm.identity.model.entity.Role;
import com.ecm.identity.model.entity.User;
import com.ecm.identity.repository.PermissionRepository;
import com.ecm.identity.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Core identity service: user sync on login, session DTO construction.
 *
 * Sprint G change:
 * - resolveRoleNames() method REMOVED. New users are provisioned with NO roles.
 *   An ECM admin must explicitly assign roles via the admin UI or UserAdminService.
 *   Users with no roles see the ECM_NO_ACCESS page in the frontend.
 * - buildSessionDto() now includes permissions[] field for the /api/auth/me response.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdentityService {

    private final UserRepository       userRepository;
    private final PermissionRepository permissionRepository;

    // ── Called on every login — creates user if first time ───────────────────

    @Transactional
    public User syncUserFromToken(Jwt jwt) {
        String subject     = jwt.getSubject();
        String email       = jwt.getClaimAsString("email");
        String displayName = jwt.getClaimAsString("name");

        return userRepository.findByEntraObjectId(subject)
                .map(existing -> {
                    // Known user (sub already bound) — just update last_login and name
                    existing.setLastLogin(OffsetDateTime.now());
                    if (displayName != null) existing.setDisplayName(displayName);
                    log.debug("Updated user login: {}", email);
                    return userRepository.save(existing);
                })
                .orElseGet(() -> {
                    // Sub not found — check if this email belongs to an invited user.
                    //
                    // Case A: EnrichmentService already activated them (entra_object_id set,
                    //         is_active=true) — findByEmail finds them, we just update last_login.
                    //
                    // Case B: EnrichmentService hasn't run yet, or user arrived via /api/auth/me
                    //         directly (edge case) — entra_object_id IS NULL, is_active=false.
                    //         We bind the sub and activate here.
                    //
                    // Case C: Truly new user — email not in DB at all → provision with no roles.
                    if (email != null) {
                        Optional<User> byEmail = userRepository.findByEmail(email);
                        if (byEmail.isPresent()) {
                            User invited = byEmail.get();
                            log.info("Binding SSO sub to existing user on first login: email={}, userId={}",
                                    email, invited.getId());

                            invited.setEntraObjectId(subject);
                            invited.setIsActive(true);
                            invited.setLastLogin(OffsetDateTime.now());
                            if (displayName != null) invited.setDisplayName(displayName);
                            return userRepository.save(invited);
                        }
                    }

                    // Case C: Truly new user — first time anyone with this identity has logged in.
                    // Sprint G: provisioned with NO roles. Admin must assign via ECM admin UI.
                    log.info("First login — provisioning new user with no roles: {}", email);

                    User newUser = User.builder()
                            .entraObjectId(subject)
                            .email(email)
                            .displayName(displayName)
                            .isActive(true)
                            .lastLogin(OffsetDateTime.now())
                            .roles(new HashSet<>())
                            .build();

                    return userRepository.save(newUser);
                });
    }

    // ── Build the session response for /api/auth/me ───────────────────────────

    @Transactional(readOnly = true)
    public UserSessionDto buildSessionDto(Jwt jwt) {
        String subject = jwt.getSubject();

        User user = userRepository
                .findByEntraObjectIdWithRoles(subject)
                .orElseThrow(() ->
                        new ResourceNotFoundException("User: " + subject));

        Set<String> roles = user.getRoles().stream()
                .map(Role::getName)
                .collect(Collectors.toSet());

        // Sprint G: Resolve permissions for all assigned roles
        Set<String> permissions = Collections.emptySet();
        if (!roles.isEmpty()) {
            List<Integer> roleIds = user.getRoles().stream()
                    .map(Role::getId)
                    .collect(Collectors.toList());
            permissions = permissionRepository.findCodesByRoleIds(roleIds);
        }

        return UserSessionDto.builder()
                .id(user.getId())
                .oktaSubject(subject)
                .email(user.getEmail())
                .displayName(user.getDisplayName())
                .departmentId(user.getDepartmentId())
                .roles(roles)
                .permissions(permissions)
                .lastLogin(user.getLastLogin())
                .tokenExpiry(jwt.getExpiresAt() != null
                        ? jwt.getExpiresAt().toString() : null)
                .build();
    }

    // ── Get full profile (for admin user management) ──────────────────────────

    @Transactional(readOnly = true)
    public UserProfileDto getUserProfile(String subject) {
        User user = userRepository
                .findByEntraObjectIdWithRoles(subject)
                .orElseThrow(() ->
                        new ResourceNotFoundException("User " + subject));
        return mapToProfileDto(user);
    }

    // ── Get all active users (admin only) ─────────────────────────────────────

    @Transactional(readOnly = true)
    public List<UserProfileDto> getAllActiveUsers() {
        return userRepository.findByIsActiveTrue()
                .stream()
                .map(this::mapToProfileDto)
                .collect(Collectors.toList());
    }

    // ── Deactivate a user (admin only) ────────────────────────────────────────

    @Transactional
    public void deactivateUser(Integer userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("User: " + userId));
        user.setIsActive(false);
        userRepository.save(user);
        log.info("Deactivated user id={}", userId);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private UserProfileDto mapToProfileDto(User user) {
        Set<String> roles = user.getRoles().stream()
                .map(Role::getName)
                .collect(Collectors.toSet());

        return UserProfileDto.builder()
                .id(user.getId())
                .entraObjectId(user.getEntraObjectId())
                .email(user.getEmail())
                .displayName(user.getDisplayName())
                .departmentId(user.getDepartmentId())
                .isActive(user.getIsActive())
                .roles(roles)
                .lastLogin(user.getLastLogin())
                .createdAt(user.getCreatedAt())
                .build();
    }
}