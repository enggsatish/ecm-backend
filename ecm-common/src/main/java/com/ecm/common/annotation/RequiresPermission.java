package com.ecm.common.annotation;

import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.annotation.*;

/**
 * Fine-grained permission gate for new endpoints.
 *
 * Usage:
 *   @RequiresPermission("documents:export")
 *   public ResponseEntity<?> exportDocuments(...) {}
 *
 *   @RequiresPermission("admin:roles")
 *   public ResponseEntity<?> createRole(...) {}
 *
 * IMPORTANT: Do NOT replace existing @PreAuthorize("hasRole(...)") annotations.
 * This annotation is for NEW endpoints that need permission-level (not role-level)
 * access control. Existing annotations are unchanged per Sprint G spec.
 *
 * The authority checked is "PERMISSION_{value}" — e.g. "PERMISSION_documents:export".
 * EcmJwtConverter emits PERMISSION_* authorities from the X-ECM-Permissions header.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@PreAuthorize("hasAuthority('PERMISSION_' + '{value}')")
public @interface RequiresPermission {

    /**
     * The permission code to require, e.g. "documents:export", "admin:roles".
     */
    String value();
}
