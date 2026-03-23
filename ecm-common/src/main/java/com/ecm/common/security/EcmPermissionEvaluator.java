package com.ecm.common.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.Set;

/**
 * Spring Security PermissionEvaluator for ECM fine-grained permissions.
 *
 * Enables hasPermission() expressions in @PreAuthorize annotations:
 *   @PreAuthorize("hasPermission(null, 'documents:export')")
 *
 * Checks for PERMISSION_{permissionCode} in the authentication's authorities.
 * These authorities are emitted by EcmJwtConverter from the X-ECM-Permissions header.
 *
 * Registered in SecurityConfig as the MethodSecurityExpressionHandler's evaluator.
 */
@Slf4j
@Component
public class EcmPermissionEvaluator implements PermissionEvaluator {

    /**
     * hasPermission(targetDomainObject, permission) — target object variant.
     * targetDomainObject is unused here; permission is the ECM permission code.
     */
    @Override
    public boolean hasPermission(Authentication authentication, Object targetDomainObject,
                                  Object permission) {
        return checkPermission(authentication, permission);
    }

    /**
     * hasPermission(targetId, targetType, permission) — ID-based variant.
     * targetId and targetType are unused here.
     */
    @Override
    public boolean hasPermission(Authentication authentication, Serializable targetId,
                                  String targetType, Object permission) {
        return checkPermission(authentication, permission);
    }

    private boolean checkPermission(Authentication authentication, Object permission) {
        if (authentication == null || permission == null) {
            return false;
        }

        String requiredAuthority = "PERMISSION_" + permission;

        Set<String> authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(java.util.stream.Collectors.toSet());

        boolean granted = authorities.contains(requiredAuthority);

        // Fallback: if no PERMISSION_* authorities exist at all (local dev without gateway),
        // grant all permissions to ADMIN and SUPER_ADMIN roles.
        if (!granted) {
            boolean hasAnyPermission = authorities.stream()
                    .anyMatch(a -> a.startsWith("PERMISSION_"));
            if (!hasAnyPermission) {
                boolean isAdmin = authorities.contains("ROLE_ECM_ADMIN")
                               || authorities.contains("ROLE_ECM_SUPER_ADMIN");
                if (isAdmin) {
                    log.debug("Permission fallback: no permissions in context, granting {} to admin user={}",
                              permission, authentication.getName());
                    return true;
                }
            }
        }

        if (!granted) {
            log.debug("Permission denied: required={}, user={}",
                      requiredAuthority, authentication.getName());
        }

        return granted;
    }
}
