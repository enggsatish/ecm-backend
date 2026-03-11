package com.ecm.common.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import java.io.Serializable;

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

        boolean granted = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(requiredAuthority::equals);

        if (!granted) {
            log.debug("Permission denied: required={}, user={}",
                      requiredAuthority, authentication.getName());
        }

        return granted;
    }
}
