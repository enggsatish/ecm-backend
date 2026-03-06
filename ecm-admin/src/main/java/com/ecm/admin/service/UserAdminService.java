package com.ecm.admin.service;

import com.ecm.admin.config.AdminRabbitConfig;
import com.ecm.admin.dto.AdminUserDto;
import com.ecm.admin.entity.*;
import com.ecm.admin.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
public class UserAdminService {

    private static final Logger log = LoggerFactory.getLogger(UserAdminService.class);

    private final AdminUserViewRepository userRepo;
    private final RoleViewRepository      roleRepo;
    private final UserRoleViewRepository  userRoleRepo;
    private final DepartmentRepository    deptRepo;
    private final RabbitTemplate          rabbit;
    private final JdbcTemplate            jdbc;

    public UserAdminService(AdminUserViewRepository userRepo,
                            RoleViewRepository roleRepo,
                            UserRoleViewRepository userRoleRepo,
                            DepartmentRepository deptRepo,
                            RabbitTemplate rabbit,
                            JdbcTemplate jdbc) {
        this.userRepo     = userRepo;
        this.roleRepo     = roleRepo;
        this.userRoleRepo = userRoleRepo;
        this.deptRepo     = deptRepo;
        this.rabbit       = rabbit;
        this.jdbc         = jdbc;
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

    /**
     * Update profile fields via JdbcTemplate.
     * ecm_core.users is owned by ecm-identity; Flyway for that module controls DDL.
     * Note: init.sql includes updated_at so we set it here.
     */
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
        return getById(userId);
    }

    public AdminUserDto removeRole(Integer userId, String roleName) {
        findUserOrThrow(userId);
        RoleView role = roleRepo.findByName(roleName)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Role not found: " + roleName));
        jdbc.update("DELETE FROM ecm_core.user_roles WHERE user_id = ? AND role_id = ?",
                userId, role.getId());
        return getById(userId);
    }

    public void deactivate(Integer id, String deactivatedBy) {
        AdminUserView user = findUserOrThrow(id);
        jdbc.update("UPDATE ecm_core.users SET is_active = false, updated_at = NOW() WHERE id = ?", id);
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
}
