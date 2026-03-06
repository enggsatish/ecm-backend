package com.ecm.common.audit;

import java.lang.annotation.*;

/**
 * Marks a method for async audit logging via AuditAspect + AuditWriter.
 *
 * Usage:
 *   // Minimal
 *   @AuditLog(event = "TEMPLATE_PUBLISHED", resourceType = "WORKFLOW_TEMPLATE")
 *
 *   // With resource identity (recommended for document/form/user operations)
 *   @AuditLog(event = "DOCUMENT_DOWNLOADED", resourceType = "DOCUMENT", resourceId = "#id")
 *
 * resourceId supports a simple SpEL-lite shorthand:
 *   "#id"          → reads @PathVariable named 'id' from method args
 *   "#documentId"  → reads param named 'documentId'
 *   "static-value" → used as-is (for fixed resource names)
 *   ""             → omitted (default)
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AuditLog {

    /** Business event name. Convention: NOUN_VERB in SCREAMING_SNAKE_CASE. */
    String event();

    /** Resource domain. Examples: DOCUMENT, WORKFLOW_TEMPLATE, SYSTEM, USER, FORM */
    String resourceType() default "SYSTEM";

    /** Severity level written to audit_log.severity */
    String severity() default "INFO";

    /**
     * Optional: name of the method parameter holding the resource identifier.
     * Prefix with '#' to reference a parameter by name.
     * Example: resourceId = "#id" reads the 'id' PathVariable.
     * Leave empty to omit resource_id from the audit row.
     */
    String resourceId() default "";
}