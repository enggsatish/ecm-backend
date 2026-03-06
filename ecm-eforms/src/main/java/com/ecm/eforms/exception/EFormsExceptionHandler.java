package com.ecm.eforms.exception;

import com.ecm.common.model.ApiResponse;
import com.ecm.eforms.service.FormSubmissionService.FormValidationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * eForms-specific exception handlers.
 *
 * Handles ONLY exceptions that are unique to ecm-eforms.
 * Common exceptions (AccessDeniedException, MethodArgumentNotValidException,
 * ResourceNotFoundException, Exception) are handled by ecm-common's
 * GlobalExceptionHandler — do NOT redeclare them here.
 *
 * @Order(1) ensures these handlers take priority over GlobalExceptionHandler
 * for the specific types declared here.
 */
@RestControllerAdvice
@Order(1)
@Slf4j
public class EFormsExceptionHandler {

    /**
     * Form field-level validation failure from RuleEngine / FormValidationService.
     * Returns a structured field-error map so the frontend renderer can
     * highlight individual fields. HTTP 422 Unprocessable Entity.
     */
    @ExceptionHandler(FormValidationException.class)
    public ResponseEntity<Map<String, Object>> handleFormValidation(FormValidationException ex) {
        log.warn("Form validation failed: {}", ex.getFieldErrors());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                "success",     false,
                "message",     "Form validation failed",
                "errorCode",   "EFORMS_VALIDATION_001",
                "fieldErrors", ex.getFieldErrors(),
                "formErrors",  ex.getFormErrors()
        ));
    }

    /**
     * Resource not found or bad form key / version argument.
     * HTTP 404 Not Found.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(IllegalArgumentException ex) {
        log.warn("Not found or bad argument: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(ex.getMessage(), "EFORMS_NOT_FOUND"));
    }

    /**
     * Invalid state transition — e.g. publishing an already-published form,
     * withdrawing an approved submission. HTTP 409 Conflict.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalState(IllegalStateException ex) {
        log.warn("State conflict: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(ex.getMessage(), "EFORMS_STATE_CONFLICT"));
    }

    /**
     * Ownership check failure — e.g. a user withdrawing another user's submission.
     * HTTP 403 Forbidden.
     */
    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<ApiResponse<Void>> handleOwnership(SecurityException ex) {
        log.warn("Ownership check failed: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error(ex.getMessage(), "EFORMS_OWNERSHIP_DENIED"));
    }
}