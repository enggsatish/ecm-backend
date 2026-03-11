package com.ecm.admin.service;

import com.ecm.admin.dto.BundleDto;
import com.ecm.admin.dto.PermissionDto;
import com.ecm.admin.dto.RoleDto;
import com.ecm.admin.entity.*;
import com.ecm.admin.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Role and permission management service.
 *
 * Cross-schema write rule: ALL writes to ecm_core tables use JdbcTemplate.
 * JPA entities in ecm-admin that map ecm_core tables are @Immutable — they
 * are never flushed and exist for reading only.
 *
 * Cache invalidation: Every mutation that changes which roles or permissions
 * a user has must invalidate Redis ecm:user:enrich:{sub} for all affected users.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RolePermissionService {

    private static final String CACHE_KEY_PREFIX = "ecm:user:enrich:";

    private final RoleViewRepository         roleRepo;
    private final PermissionViewRepository   permissionRepo;
    private final RolePermissionViewRepository rolePermissionRepo;
    private final BundleViewRepository       bundleRepo;
    private final BundlePermissionViewRepository bundlePermissionRepo;
    private final AdminUserViewRepository    userRepo;
    private final JdbcTemplate              jdbc;
    private final StringRedisTemplate       redis;

    // ── Roles ─────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<RoleDto> listAllRoles() {
        return roleRepo.findAll().stream()
                .map(this::toRoleDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public RoleDto getRole(Integer id) {
        RoleView role = findRoleOrThrow(id);
        return toRoleDto(role);
    }

    @Transactional
    public RoleDto createRole(RoleDto.CreateRequest req) {
        if (req.getName() == null || !req.getName().toUpperCase().startsWith("ECM_")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Role name must start with ECM_ prefix");
        }
        String upperName = req.getName().toUpperCase();
        if (roleRepo.findByName(upperName).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Role already exists: " + upperName);
        }

        jdbc.update(
                "INSERT INTO ecm_core.roles (name, description, is_system, is_active, created_at, updated_at) " +
                        "VALUES (?, ?, false, true, NOW(), NOW())",
                upperName, req.getDescription()
        );

        return toRoleDto(roleRepo.findByName(upperName)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "Role creation failed")));
    }

    @Transactional
    public RoleDto updateRole(Integer id, RoleDto.UpdateRequest req) {
        RoleView role = findRoleOrThrow(id);
        if (Boolean.TRUE.equals(role.getIsSystem())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "System roles cannot be modified");
        }

        List<String> setClauses = new ArrayList<>();
        List<Object> params = new ArrayList<>();

        if (req.getName() != null) {
            if (!req.getName().toUpperCase().startsWith("ECM_")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Role name must start with ECM_ prefix");
            }
            setClauses.add("name = ?");
            params.add(req.getName().toUpperCase());
        }
        if (req.getDescription() != null) {
            setClauses.add("description = ?");
            params.add(req.getDescription());
        }
        if (!setClauses.isEmpty()) {
            setClauses.add("updated_at = NOW()");
            params.add(id);
            jdbc.update(
                    "UPDATE ecm_core.roles SET " + String.join(", ", setClauses) + " WHERE id = ?",
                    params.toArray()
            );
        }

        return getRole(id);
    }

    @Transactional
    public void deleteRole(Integer id) {
        RoleView role = findRoleOrThrow(id);

        if (Boolean.TRUE.equals(role.getIsSystem())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "System roles cannot be deleted");
        }

        // Cascade: collect affected user subs BEFORE removing rows (needed for cache bust)
        List<String> affectedSubs = jdbc.queryForList(
                "SELECT u.entra_object_id FROM ecm_core.users u " +
                        "JOIN ecm_core.user_roles ur ON ur.user_id = u.id " +
                        "WHERE ur.role_id = ? AND u.entra_object_id IS NOT NULL",
                String.class, id);

        long userCount = countUsersWithRole(id);
        if (userCount > 0) {
            log.info("Cascade-removing role id={} name={} from {} user(s) before deletion",
                    id, role.getName(), userCount);

            // Remove role assignment from all users
            jdbc.update("DELETE FROM ecm_core.user_roles WHERE role_id = ?", id);

            // Bust Redis enrichment cache for every affected user so their
            // permissions re-evaluate on the next gateway request
            affectedSubs.forEach(sub -> {
                redis.delete(CACHE_KEY_PREFIX + sub);
                log.debug("Invalidated enrichment cache for sub={} (role {} deleted)", sub, id);
            });
        }

        // Remove all permission assignments for this role
        jdbc.update("DELETE FROM ecm_core.role_permissions WHERE role_id = ?", id);

        // Soft-delete the role itself (is_active = false keeps audit history intact)
        jdbc.update(
                "UPDATE ecm_core.roles SET is_active = false, updated_at = NOW() WHERE id = ?", id);

        log.info("Soft-deleted role id={} name={} (cascaded {} user assignment(s))",
                id, role.getName(), userCount);
    }

    // ── Role Permissions ──────────────────────────────────────────────────────

    @Transactional
    public RoleDto addPermissionToRole(Integer roleId, String permissionCode) {
        findRoleOrThrow(roleId);

        PermissionView perm = permissionRepo.findByCode(permissionCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Permission not found: " + permissionCode));

        boolean exists = rolePermissionRepo
                .existsByRoleIdAndPermissionId(roleId, perm.getId());
        if (exists) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Role already has permission: " + permissionCode);
        }

        jdbc.update(
                "INSERT INTO ecm_core.role_permissions (role_id, permission_id, granted_by, granted_at) " +
                        "VALUES (?, ?, 'admin', NOW())",
                roleId, perm.getId()
        );

        invalidateCacheForRole(roleId);
        return getRole(roleId);
    }

    @Transactional
    public RoleDto removePermissionFromRole(Integer roleId, String permissionCode) {
        findRoleOrThrow(roleId);

        PermissionView perm = permissionRepo.findByCode(permissionCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Permission not found: " + permissionCode));

        jdbc.update(
                "DELETE FROM ecm_core.role_permissions WHERE role_id = ? AND permission_id = ?",
                roleId, perm.getId()
        );

        invalidateCacheForRole(roleId);
        return getRole(roleId);
    }

    @Transactional
    public RoleDto applyBundleToRole(Integer roleId, Integer bundleId) {
        findRoleOrThrow(roleId);

        bundleRepo.findById(bundleId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Bundle not found: " + bundleId));

        // Get all permissions in the bundle
        List<Integer> bundlePermIds = bundlePermissionRepo
                .findPermissionIdsByBundleId(bundleId);

        // Insert permissions not already assigned
        for (Integer permId : bundlePermIds) {
            boolean exists = rolePermissionRepo.existsByRoleIdAndPermissionId(roleId, permId);
            if (!exists) {
                jdbc.update(
                        "INSERT INTO ecm_core.role_permissions (role_id, permission_id, granted_by, granted_at) " +
                                "VALUES (?, ?, 'admin_bundle', NOW())",
                        roleId, permId
                );
            }
        }

        invalidateCacheForRole(roleId);
        return getRole(roleId);
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<PermissionDto> listAllPermissions() {
        return permissionRepo.findAllByOrderByModuleCodeAscActionAsc().stream()
                .map(p -> PermissionDto.builder()
                        .id(p.getId())
                        .code(p.getCode())
                        .moduleCode(p.getModuleCode())
                        .action(p.getAction())
                        .description(p.getDescription())
                        .isActive(p.getIsActive())
                        .build())
                .collect(Collectors.toList());
    }

    // ── Bundles ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<BundleDto> listAllBundles() {
        return bundleRepo.findAllByOrderBySortOrderAsc().stream()
                .map(b -> {
                    List<String> perms = bundlePermissionRepo
                            .findPermissionIdsByBundleId(b.getId())
                            .stream()
                            .map(pid -> permissionRepo.findById(pid)
                                    .map(PermissionView::getCode).orElse(null))
                            .filter(Objects::nonNull)
                            .collect(Collectors.toList());
                    return BundleDto.builder()
                            .id(b.getId())
                            .code(b.getCode())
                            .name(b.getName())
                            .description(b.getDescription())
                            .isSystem(b.getIsSystem())
                            .permissions(perms)
                            .build();
                })
                .collect(Collectors.toList());
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private RoleView findRoleOrThrow(Integer id) {
        return roleRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Role not found: " + id));
    }

    private RoleDto toRoleDto(RoleView role) {
        List<Integer> permIds = rolePermissionRepo.findPermissionIdsByRoleId(role.getId());
        List<String> permCodes = permIds.stream()
                .map(pid -> permissionRepo.findById(pid)
                        .map(PermissionView::getCode).orElse(null))
                .filter(Objects::nonNull)
                .sorted()
                .collect(Collectors.toList());

        long userCount = countUsersWithRole(role.getId());

        return RoleDto.builder()
                .id(role.getId())
                .name(role.getName())
                .description(role.getDescription())
                .isSystem(role.getIsSystem())
                .isActive(role.getIsActive())
                .permissions(permCodes)
                .userCount(userCount)
                .build();
    }

    private long countUsersWithRole(Integer roleId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ecm_core.user_roles WHERE role_id = ?",
                Long.class, roleId);
        return count != null ? count : 0L;
    }

    /**
     * Invalidates Redis enrichment cache for all users who have the given role.
     * Called whenever role permissions change so the next request re-enriches.
     */
    private void invalidateCacheForRole(Integer roleId) {
        List<String> subs = jdbc.queryForList(
                "SELECT u.entra_object_id FROM ecm_core.users u " +
                        "JOIN ecm_core.user_roles ur ON ur.user_id = u.id " +
                        "WHERE ur.role_id = ? AND u.entra_object_id IS NOT NULL",
                String.class, roleId);

        subs.forEach(sub -> {
            redis.delete(CACHE_KEY_PREFIX + sub);
            log.debug("Invalidated enrichment cache for sub={} (role {} changed)", sub, roleId);
        });

        if (!subs.isEmpty()) {
            log.info("Invalidated enrichment cache for {} users due to role {} permission change",
                    subs.size(), roleId);
        }
    }
}