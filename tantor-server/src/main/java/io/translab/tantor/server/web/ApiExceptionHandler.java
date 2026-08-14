package io.translab.tantor.server.web;

import io.translab.tantor.server.service.ClusterNameConflictException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
@Slf4j
public class ApiExceptionHandler {

    @ExceptionHandler(ClusterNameConflictException.class)
    public ResponseEntity<Map<String, String>> handleClusterNameConflict(ClusterNameConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", cleanMessage(ex)));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("message", cleanMessage(ex)));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> handleDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation", ex);
        String cause = ex.getRootCause() != null ? ex.getRootCause().getMessage() : ex.getMessage();
        if (cause != null && cause.contains("ux_kf_clusters_active_name_ci")) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "message", "A cluster with this name already exists. Choose a different name."
            ));
        }
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
            "message",
            "Data Conflict: " + cause
        ));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> handleRuntime(RuntimeException ex) {
        log.warn("Request failed", ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("message", cleanMessage(ex)));
    }

    private String cleanMessage(Exception ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? "The request could not be completed." : message;
    }
}
