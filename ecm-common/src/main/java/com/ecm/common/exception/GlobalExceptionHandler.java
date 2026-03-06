package com.ecm.common.exception;

import com.ecm.common.model.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.stream.Collectors;

/**
 * Single @RestControllerAdvice for ALL ECM services.
 * Every module that depends on ecm-common gets this handler for free.
 *
 * All responses use ApiResponse<Void> for consistency — the frontend
 * always receives the same { success, message, errorCode, timestamp } shape.
 *
 * Modules must NOT define their own @RestControllerAdvice — that caused
 * duplicate bean conflicts and inconsistent response formats.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ── 401 ────────────────────────────────────────────────────────────────────

    @ExceptionHandler(InvalidBearerTokenException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvalidToken(
            InvalidBearerTokenException ex) {
        log.warn("Invalid bearer token: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error("Invalid or expired token", "AUTH_001"));
    }

    // ── 403 ────────────────────────────────────────────────────────────────────

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(
            AccessDeniedException ex) {
        log.warn("Access denied: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error(
                        "You do not have permission to perform this action",
                        "AUTH_002"));
    }

    // ── 404 — NOW CATCHES THE CORRECT EXCEPTION ────────────────────────────────
    // Previously caught InvalidConfigurationPropertyValueException (a Spring Boot
    // startup class that is NEVER thrown during HTTP requests). Fixed to catch
    // ResourceNotFoundException which is thrown by all ECM service layers.

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(
            ResourceNotFoundException ex) {
        log.debug("Resource not found: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(ex.getMessage(), "NOT_FOUND_001"));
    }

    // ── 400 — Validation ───────────────────────────────────────────────────────

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(
            MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(message, "VALIDATION_001"));
    }

    // ── 413 — File too large (ecm-document uploads) ────────────────────────────

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleFileTooLarge(
            MaxUploadSizeExceededException ex) {
        return ResponseEntity
                .status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiResponse.error(
                        "Uploaded file exceeds the maximum allowed size of 50 MB",
                        "FILE_TOO_LARGE"));
    }

    // ── 500 — Unexpected ───────────────────────────────────────────────────────

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneral(Exception ex) {
        log.error("Unhandled exception [{}]: {}", ex.getClass().getSimpleName(), ex.getMessage(), ex);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("An unexpected error occurred", "SERVER_001"));
    }
}