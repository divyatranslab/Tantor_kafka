package io.translab.tantor.artifact.exception;

/**
 * Thrown when an end-to-end operation deadline is exceeded during artifact processing.
 * Extends {@link UploadLimitExceededException} to maintain backward compatibility and map to HTTP 503.
 */
public class OperationTimeoutException extends UploadLimitExceededException {

    public OperationTimeoutException(String message) {
        super(message);
    }

    public OperationTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
