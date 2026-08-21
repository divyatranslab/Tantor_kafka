package io.translab.tantor.server.web;

import io.translab.tantor.server.service.ClusterNameConflictException;
import io.translab.tantor.server.service.CanonicalClusterNotFoundException;
import io.translab.tantor.server.service.CanonicalIdentityException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.UUID;

@RestControllerAdvice
@Slf4j
public class ApiExceptionHandler {

    private String getCorrelationId() {
        String id = MDC.get("correlationId");
        return id != null ? id : UUID.randomUUID().toString();
    }

    private ApiError buildError(String errorCode, String message) {
        return ApiError.builder()
                .errorCode(errorCode)
                .message(message)
                .correlationId(getCorrelationId())
                .timestamp(Instant.now())
                .build();
    }

    @ExceptionHandler(CanonicalClusterNotFoundException.class)
    public ResponseEntity<ApiError> handleCanonicalClusterNotFound(CanonicalClusterNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(buildError("NOT_FOUND", cleanMessage(ex)));
    }

    @ExceptionHandler(CanonicalIdentityException.class)
    public ResponseEntity<ApiError> handleCanonicalIdentity(CanonicalIdentityException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(buildError("CONFLICT", cleanMessage(ex)));
    }

    @ExceptionHandler(ClusterNameConflictException.class)
    public ResponseEntity<ApiError> handleClusterNameConflict(ClusterNameConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(buildError("CLUSTER_NAME_CONFLICT", cleanMessage(ex)));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest()
                .body(buildError("BAD_REQUEST", cleanMessage(ex)));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation", ex);
        String cause = ex.getRootCause() != null ? ex.getRootCause().getMessage() : ex.getMessage();
        if (cause != null && cause.contains("ux_kf_clusters_active_name_ci")) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(buildError(
                    "DATA_CONFLICT", "A cluster with this name already exists. Choose a different name."
            ));
        }
        // Do not leak raw DB exceptions
        return ResponseEntity.status(HttpStatus.CONFLICT).body(buildError(
            "DATA_CONFLICT",
            "A data integrity conflict occurred. Please check your input."
        ));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiError> handleRuntime(RuntimeException ex) {
        log.error("Unhandled runtime exception", ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(buildError(
            "INTERNAL_ERROR", "The request could not be completed due to an internal error."
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleException(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(buildError(
            "INTERNAL_ERROR", "An unexpected internal error occurred."
        ));
    }

    private String cleanMessage(Exception ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? "The request could not be completed." : message;
    }
}
