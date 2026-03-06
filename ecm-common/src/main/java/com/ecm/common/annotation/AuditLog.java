package com.ecm.common.annotation;

import java.lang.annotation.*;

/**
 * Marks a method for audit logging.
 * The AuditAspect intercepts methods annotated with @AuditLog,
 * extracts the JWT principal, and writes a row to ecm_audit.audit_log.
 *
 * Usage:
 *   @AuditLog(event = "DOCUMENT_UPLOADED", resourceType = "DOCUMENT")
 *   public ResponseEntity<...> upload(...) { }
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AuditLog {

    /**
     * Business event name — written to audit_log.event_type.
     * Convention: NOUN_VERB in SCREAMING_SNAKE_CASE.
     * Examples: DOCUMENT_UPLOADED, TEMPLATE_PUBLISHED, USER_DEACTIVATED
     */
    String event();

    /**
     * The resource domain this event belongs to.
     * Examples: DOCUMENT, WORKFLOW_TEMPLATE, SYSTEM, USER, FORM
     */
    String resourceType();

    /**
     * Optional description override. Defaults to empty — the aspect
     * builds a description from method name + args if empty.
     */
    String description() default "";
}