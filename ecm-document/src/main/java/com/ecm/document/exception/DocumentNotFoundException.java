package com.ecm.document.exception;

import com.ecm.common.exception.ResourceNotFoundException;

import java.util.UUID;

/**
 * Thrown when a document UUID is not found or has been soft-deleted.
 * Extends ecm-common's ResourceNotFoundException so GlobalExceptionHandler
 * catches it and returns a consistent 404 ApiResponse.
 */
public class DocumentNotFoundException extends ResourceNotFoundException {

    public DocumentNotFoundException(UUID id) {
        super("Document", id);
    }

    public DocumentNotFoundException(String message) {
        super(message);
    }
}