package io.translab.tantor.server.web;

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

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("message", cleanMessage(ex)));
    }

    @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidationExceptions(org.springframework.web.bind.MethodArgumentNotValidException ex) {
        String errorMessage = ex.getBindingResult().getAllErrors().stream()
                .map(org.springframework.validation.ObjectError::getDefaultMessage)
                .findFirst()
                .orElse("Validation failed");
        // We use 'message' or 'error' depending on what the UI expected, but UI generally handles both if we use standard error objects, or we can use "message" and "error".
        // Let's use both to be safe for all endpoints.
        return ResponseEntity.badRequest().body(Map.of("message", errorMessage, "error", errorMessage));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> handleDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation", ex);
        String cause = ex.getRootCause() != null ? ex.getRootCause().getMessage() : ex.getMessage();
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
            "message",
            "Data Conflict: " + cause
        ));
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public void rethrowAccessDenied(
            org.springframework.security.access.AccessDeniedException exception
    ) {
        throw exception;
    }

    @ExceptionHandler(org.springframework.security.core.AuthenticationException.class)
    public void rethrowAuthentication(
            org.springframework.security.core.AuthenticationException exception
    ) {
        throw exception;
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<org.springframework.http.ProblemDetail> handleRuntime(RuntimeException ex) {
        log.error("Unexpected server error", ex);
        org.springframework.http.ProblemDetail problem = org.springframework.http.ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected internal server error occurred.");
        problem.setTitle("Internal Server Error");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
    }

    private String cleanMessage(Exception ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? "The request could not be completed." : message;
    }
}
