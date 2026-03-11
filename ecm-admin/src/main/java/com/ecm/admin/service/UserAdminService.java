package com.ecm.admin.service;

import com.ecm.admin.config.AdminRabbitConfig;
import com.ecm.admin.dto.AdminUserDto;
import com.ecm.admin.entity.*;
import com.ecm.admin.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * User administration service.
 *
 * Sprint G: Added Redis enrichment cache invalidation to addRole(), removeRole(),
 * deactivate(), and reactivate(). After any role or activation change, the cached
 * ecm:user:enrich:{sub} entry is deleted so the next gateway request re-enriches.
 */
@Service
@Transactional
public class UserAdminService {

    private static final Logger log = LoggerFactory.getLogger(UserAdminService.class);
    private static final String CACHE_KEY_PREFIX = "ecm:user:enrich:";

    private final AdminUserViewRepository userRepo;
    private final RoleViewRepository      roleRepo;
    private final UserRoleViewRepository  userRoleRepo;
    private final DepartmentRepository    deptRepo;
    private final RabbitTemplate          rabbit;
    private final JdbcTemplate            jdbc;
    private final StringRedisTemplate     redis;   // Sprint G

    public UserAdminService(AdminUserViewRepository userRepo,
                            RoleViewRepository roleRepo,
                            UserRoleViewRepository userRoleRepo,
                            DepartmentRepository deptRepo,
                            RabbitTemplate rabbit,
                            JdbcTemplate jdbc,
                            StringRedisTemplate redis) {
        this.userRepo     = userRepo;
        this.roleRepo     = roleRepo;
        this.userRoleRepo = userRoleRepo;
        this.deptRepo     = deptRepo;
        this.rabbit       = rabbit;
        this.jdbc         = jdbc;
        this.redis        = redis;
    }

    // ── Queries ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<AdminUserDto> search(Boolean isActive, Integer departmentId,
                                     String search, Pageable pageable) {
        return userRepo.search(isActive, departmentId, search, pageable)
                .map(this::enrichUser);
    }

    @Transactional(readOnly = true)
    public AdminUserDto getById(Integer id) {
        return enrichUser(findUserOrThrow(id));
    }

    // ── Commands ───────────────────────────────────────────────────────────

    public AdminUserDto update(Integer id, AdminUserDto.UpdateRequest req) {
        findUserOrThrow(id);

        List<String> setClauses = new ArrayList<>();
        List<Object> params     = new ArrayList<>();

        if (req.getDisplayName() != null) {
            setClauses.add("display_name = ?");
            params.add(req.getDisplayName().trim());
        }
        if (req.getDepartmentId() != null) {
            setClauses.add("department_id = ?");
            params.add(req.getDepartmentId());
        }
        if (req.getIsActive() != null) {
            setClauses.add("is_active = ?");
            params.add(req.getIsActive());
        }
        if (!setClauses.isEmpty()) {
            setClauses.add("updated_at = NOW()");
            params.add(id);
            jdbc.update(
                    "UPDATE ecm_core.users SET " + String.join(", ", setClauses) + " WHERE id = ?",
                    params.toArray()
            );
        }
        return enrichUser(findUserOrThrow(id));
    }

    public AdminUserDto addRole(Integer userId, String roleName) {
        findUserOrThrow(userId);
        RoleView role = roleRepo.findByName(roleName)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Role not found: " + roleName));
        if (userRoleRepo.findRoleIdsByUserId(userId).contains(role.getId()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "User already has role: " + roleName);

        jdbc.update("INSERT INTO ecm_core.user_roles (user_id, role_id) VALUES (?, ?)",
                userId, role.getId());

        // Sprint G: Bust enrichment cache — role change must be reflected immediately
        invalidateUserCache(userId);

        return getById(userId);
    }

    public AdminUserDto removeRole(Integer userId, String roleName) {
        findUserOrThrow(userId);
        RoleView role = roleRepo.findByName(roleName)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Role not found: " + roleName));

        jdbc.update("DELETE FROM ecm_core.user_roles WHERE user_id = ? AND role_id = ?",
                userId, role.getId());

        // Sprint G: Bust enrichment cache
        invalidateUserCache(userId);

        return getById(userId);
    }

    public void deactivate(Integer id, String deactivatedBy) {
        AdminUserView user = findUserOrThrow(id);
        jdbc.update("UPDATE ecm_core.users SET is_active = false, updated_at = NOW() WHERE id = ?", id);

        // Sprint G: Bust enrichment cache — deactivated users must get NO_ACCESS immediately
        invalidateUserCache(id);

        try {
            rabbit.convertAndSend(
                    AdminRabbitConfig.ADMIN_EXCHANGE,
                    AdminRabbitConfig.RK_USER_DEACTIVATED,
                    Map.of(
                            "userId",         id,
                            "email",          user.getEmail(),
                            "entraObjectId",  user.getEntraObjectId() != null ? user.getEntraObjectId() : "",
                            "deactivatedBy",  deactivatedBy,
                            "timestamp",      OffsetDateTime.now().toString()
                    )
            );
        } catch (Exception e) {
            log.warn("Failed to publish user.deactivated event: {}", e.getMessage());
        }
    }

    public void reactivate(Integer id) {
        findUserOrThrow(id);
        jdbc.update("UPDATE ecm_core.users SET is_active = true, updated_at = NOW() WHERE id = ?", id);

        // Sprint G: Bust enrichment cache — reactivated user needs fresh enrichment
        invalidateUserCache(id);
    }

    /**
     * Creates a pending user record for a not-yet-registered SSO user.
     *
     * Flow:
     *   1. Validate the email is not already registered.
     *   2. Insert a users row with is_active=false, auth_provider='PENDING'.
     *      entra_object_id is left NULL — it is populated on first SSO login by
     *      EcmJwtConverter when it matches by email.
     *   3. If an initialRole is provided (default ECM_READONLY), assign it in
     *      user_roles so the user gets correct permissions the moment they log in.
     *
     * The user does NOT need to receive an email from this service — EntraID / Okta
     * handles invitation emails separately. This endpoint just pre-registers the user
     * in ECM so role assignment happens before first login.
     */
    public AdminUserDto inviteUser(AdminUserDto.InviteRequest req) {
        String email = req.getEmail().trim().toLowerCase();

        // Check for duplicate email
        Integer existing = null;
        try {
            existing = jdbc.queryForObject(
                    "SELECT id FROM ecm_core.users WHERE email = ?",
                    Integer.class, email);
        } catch (Exception ignored) { /* no row = good */ }

        if (existing != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A user with email '" + email + "' already exists");
        }

        // Validate department if provided
        if (req.getDepartmentId() != null) {
            deptRepo.findById(req.getDepartmentId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "Department not found: " + req.getDepartmentId()));
        }

        // Resolve initial role (default = ECM_READONLY)
        String roleName = (req.getInitialRole() != null && !req.getInitialRole().isBlank())
                ? req.getInitialRole().trim().toUpperCase()
                : "ECM_READONLY";

        RoleView role = roleRepo.findByName(roleName)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Role not found: " + roleName));

        // Insert pending user — entra_object_id deliberately NULL until first login
        jdbc.update(
                "INSERT INTO ecm_core.users " +
                        "(email, entra_object_id, display_name, department_id, is_active,  created_at, updated_at) " +
                        "VALUES (?, 'PENDING', ?, ?, false, NOW(), NOW())",
                email,
                req.getDisplayName() != null ? req.getDisplayName().trim() : null,
                req.getDepartmentId()
        );

        // Retrieve the new user id
        Integer newUserId = jdbc.queryForObject(
                "SELECT id FROM ecm_core.users WHERE email = ?",
                Integer.class, email);

        // Assign initial role
        jdbc.update(
                "INSERT INTO ecm_core.user_roles (user_id, role_id) VALUES (?, ?)",
                newUserId, role.getId());

        log.info("Invited user email={} with initial role={}", email, roleName);
        return getById(newUserId);
    }

    // ── Private helpers ────────────────────────────────────────────────────

    private AdminUserView findUserOrThrow(Integer id) {
        return userRepo.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found: " + id));
    }

    private AdminUserDto enrichUser(AdminUserView user) {
        String deptName = (user.getDepartmentId() != null)
                ? deptRepo.findById(user.getDepartmentId())
                .map(Department::getName).orElse(null)
                : null;
        List<Integer> roleIds   = userRoleRepo.findRoleIdsByUserId(user.getId());
        List<String>  roleNames = roleRepo.findAllById(roleIds).stream()
                .map(RoleView::getName)
                .sorted()
                .collect(Collectors.toList());
        return AdminUserDto.from(user, deptName, roleNames);
    }

    /**
     * Sprint G: Invalidate the enrichment cache for a user by userId.
     * Reads entra_object_id directly from the DB (the user view may be stale
     * if JPA entity is cached).
     */
    private void invalidateUserCache(Integer userId) {
        String sub = jdbc.queryForObject(
                "SELECT entra_object_id FROM ecm_core.users WHERE id = ?",
                String.class, userId);
        if (sub != null && !sub.isBlank()) {
            redis.delete(CACHE_KEY_PREFIX + sub);
            log.debug("Invalidated enrichment cache for userId={}", userId);
        }
    }
}