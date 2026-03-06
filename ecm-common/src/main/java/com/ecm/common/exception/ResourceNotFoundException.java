package com.ecm.common.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Base exception for all "resource not found" cases across every ECM module.
 *
 * Centralised in ecm-common so GlobalExceptionHandler can catch it once.
 * Module-specific exceptions (DocumentNotFoundException etc.) extend this.
 *
 * Usage:
 *   throw new ResourceNotFoundException("Document", id);
 *   throw new ResourceNotFoundException("User not found: " + subject);
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class ResourceNotFoundException extends RuntimeException {

    private final String resourceType;
    private final Object resourceId;

    /** Use when you have a typed resource and ID — produces clean message. */
    public ResourceNotFoundException(String resourceType, Object resourceId) {
        super(resourceType + " not found: " + resourceId);
        this.resourceType = resourceType;
        this.resourceId   = resourceId;
    }

    /** Use for custom messages that don't fit the type/id pattern. */
    public ResourceNotFoundException(String message) {
        super(message);
        this.resourceType = "Resource";
        this.resourceId   = null;
    }

    public String getResourceType() { return resourceType; }
    public Object getResourceId()   { return resourceId; }
}