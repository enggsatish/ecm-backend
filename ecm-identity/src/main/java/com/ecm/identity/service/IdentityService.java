package com.ecm.identity.service;

import com.ecm.common.exception.ResourceNotFoundException;
import com.ecm.identity.model.dto.UserProfileDto;
import com.ecm.identity.model.dto.UserSessionDto;
import com.ecm.identity.model.entity.Role;
import com.ecm.identity.model.entity.User;
import com.ecm.identity.repository.RoleRepository;
import com.ecm.identity.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class IdentityService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;

    // ── Called on every login — creates user if first time ───────
    @Transactional
    public User syncUserFromToken(Jwt jwt) {
        String subject     = jwt.getSubject();
        String email       = jwt.getClaimAsString("email");
        String displayName = jwt.getClaimAsString("name");

        return userRepository.findByEntraObjectId(subject)
                .map(existing -> {
                    // User exists — just update login time and name
                    existing.setLastLogin(OffsetDateTime.now());
                    existing.setDisplayName(displayName);
                    log.debug("Updated user login: {}", email);
                    return userRepository.save(existing);
                })
                .orElseGet(() -> {
                    // First login — provision user in ECM database
                    log.info("First login — provisioning user: {}", email);

                    // Read groups claim from Okta token
                    List<String> groups =
                            jwt.getClaimAsStringList("groups");

                    Set<String> roleNames = resolveRoleNames(groups);
                    Set<Role> roles = roleRepository.findByNameIn(roleNames);

                    User newUser = User.builder()
                            .entraObjectId(subject)
                            .email(email)
                            .displayName(displayName)
                            .isActive(true)
                            .lastLogin(OffsetDateTime.now())
                            .roles(roles)
                            .build();

                    return userRepository.save(newUser);
                });
    }

    // ── Build the session response for /api/auth/me ───────────────
    @Transactional(readOnly = true)
    public UserSessionDto buildSessionDto(Jwt jwt) {
        String subject = jwt.getSubject();

        User user = userRepository
                .findByEntraObjectIdWithRoles(subject)
                .orElseThrow(() ->
                        new ResourceNotFoundException("User : " + subject));

        Set<String> roles = user.getRoles().stream()
                .map(Role::getName)
                .collect(Collectors.toSet());

        return UserSessionDto.builder()
                .id(user.getId())
                .oktaSubject(subject)
                .email(user.getEmail())
                .displayName(user.getDisplayName())
                .departmentId(user.getDepartmentId())
                .roles(roles)
                .lastLogin(user.getLastLogin())
                .tokenExpiry(jwt.getExpiresAt() != null
                        ? jwt.getExpiresAt().toString() : null)
                .build();
    }

    // ── Get full profile (for admin user management) ──────────────
    @Transactional(readOnly = true)
    public UserProfileDto getUserProfile(String subject) {
        User user = userRepository
                .findByEntraObjectIdWithRoles(subject)
                .orElseThrow(() ->
                        new ResourceNotFoundException("User " + subject));

        return mapToProfileDto(user);
    }

    // ── Get all active users (admin only) ─────────────────────────
    @Transactional(readOnly = true)
    public List<UserProfileDto> getAllActiveUsers() {
        return userRepository.findByIsActiveTrue()
                .stream()
                .map(this::mapToProfileDto)
                .collect(Collectors.toList());
    }

    // ── Deactivate a user (admin only) ────────────────────────────
    @Transactional
    public void deactivateUser(Integer userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("User: " + userId));
        user.setIsActive(false);
        userRepository.save(user);
        log.info("Deactivated user: {}", user.getEmail());
    }

    // ── Map Okta groups → ECM role names ─────────────────────────
    // Only groups starting with ECM_ are mapped
    private Set<String> resolveRoleNames(List<String> groups) {
        if (groups == null || groups.isEmpty()) {
            return Set.of("ECM_READONLY");   // default role
        }

        Set<String> ecmRoles = groups.stream()
                .filter(g -> g.startsWith("ECM_"))
                .collect(Collectors.toSet());

        return ecmRoles.isEmpty()
                ? Set.of("ECM_READONLY")
                : ecmRoles;
    }

    // ── DTO mapping helper ────────────────────────────────────────
    private UserProfileDto mapToProfileDto(User user) {
        return UserProfileDto.builder()
                .id(user.getId())
                .oktaSubject(user.getEntraObjectId())
                .email(user.getEmail())
                .displayName(user.getDisplayName())
                .departmentId(user.getDepartmentId())
                .roles(user.getRoles().stream()
                        .map(Role::getName)
                        .collect(Collectors.toSet()))
                .isActive(user.getIsActive())
                .lastLogin(user.getLastLogin())
                .createdAt(user.getCreatedAt())
                .build();
    }
}