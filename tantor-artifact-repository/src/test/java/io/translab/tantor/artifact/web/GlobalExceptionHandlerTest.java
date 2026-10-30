package io.translab.tantor.artifact.web;

import io.translab.tantor.artifact.exception.UploadLimitExceededException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
    }

    @Test
    void queueFullReturnsTooManyRequests() {
        UploadLimitExceededException ex = new UploadLimitExceededException("validation queue is full; try again shortly");
        ProblemDetail pd = handler.handleUploadLimit(ex);

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(pd.getTitle()).isEqualTo("Too Many Requests");
        assertThat(pd.getDetail()).isEqualTo("validation queue is full; try again shortly");
        assertThat(pd.getType()).isEqualTo(URI.create("https://docs.translab.io/tantor/errors/too-many-requests"));
    }

    @Test
    void capacityFullReturnsTooManyRequests() {
        UploadLimitExceededException ex = new UploadLimitExceededException("Validation capacity is full");
        ProblemDetail pd = handler.handleUploadLimit(ex);

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(pd.getTitle()).isEqualTo("Too Many Requests");
        assertThat(pd.getDetail()).isEqualTo("Validation capacity is full");
        assertThat(pd.getType()).isEqualTo(URI.create("https://docs.translab.io/tantor/errors/too-many-requests"));
    }

    @Test
    void concurrentUploadsLimitReturnsTooManyRequests() {
        UploadLimitExceededException ex = new UploadLimitExceededException("Too many concurrent uploads; try again shortly");
        ProblemDetail pd = handler.handleUploadLimit(ex);

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(pd.getTitle()).isEqualTo("Too Many Requests");
        assertThat(pd.getDetail()).isEqualTo("Too many concurrent uploads; try again shortly");
        assertThat(pd.getType()).isEqualTo(URI.create("https://docs.translab.io/tantor/errors/too-many-requests"));
    }

    @Test
    void queueTimeoutReturnsServiceUnavailable() {
        UploadLimitExceededException ex = new UploadLimitExceededException("malware-scan queue wait timeout exceeded; try again shortly");
        ProblemDetail pd = handler.handleUploadLimit(ex);

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
        assertThat(pd.getTitle()).isEqualTo("Service Unavailable");
        assertThat(pd.getDetail()).isEqualTo("malware-scan queue wait timeout exceeded; try again shortly");
        assertThat(pd.getType()).isEqualTo(URI.create("https://docs.translab.io/tantor/errors/service-unavailable"));
    }

    @Test
    void shutdownReturnsServiceUnavailable() {
        UploadLimitExceededException ex = new UploadLimitExceededException("validation admission controller is shutting down");
        ProblemDetail pd = handler.handleUploadLimit(ex);

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
        assertThat(pd.getTitle()).isEqualTo("Service Unavailable");
        assertThat(pd.getDetail()).isEqualTo("validation admission controller is shutting down");
        assertThat(pd.getType()).isEqualTo(URI.create("https://docs.translab.io/tantor/errors/service-unavailable"));
    }

    @Test
    void interruptedWaitReturnsServiceUnavailable() {
        UploadLimitExceededException ex = new UploadLimitExceededException("validation wait interrupted");
        ProblemDetail pd = handler.handleUploadLimit(ex);

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
        assertThat(pd.getTitle()).isEqualTo("Service Unavailable");
        assertThat(pd.getDetail()).isEqualTo("validation wait interrupted");
        assertThat(pd.getType()).isEqualTo(URI.create("https://docs.translab.io/tantor/errors/service-unavailable"));
    }

    @Test
    void oversizedUploadReturnsPayloadTooLarge() {
        UploadLimitExceededException ex = new UploadLimitExceededException("Upload exceeds the configured maximum of 104857600 bytes");
        ProblemDetail pd = handler.handleUploadLimit(ex);

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE.value());
        assertThat(pd.getTitle()).isEqualTo("Upload limit exceeded");
        assertThat(pd.getDetail()).isEqualTo("Upload exceeds the configured maximum of 104857600 bytes");
        assertThat(pd.getType()).isEqualTo(URI.create("https://docs.translab.io/tantor/errors/upload-limit-exceeded"));
    }

    @Test
    void oversizedBundleReturnsPayloadTooLarge() {
        UploadLimitExceededException ex = new UploadLimitExceededException("Bundle exceeds the configured maximum of 524288000 bytes");
        ProblemDetail pd = handler.handleUploadLimit(ex);

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE.value());
        assertThat(pd.getTitle()).isEqualTo("Upload limit exceeded");
        assertThat(pd.getDetail()).isEqualTo("Bundle exceeds the configured maximum of 524288000 bytes");
        assertThat(pd.getType()).isEqualTo(URI.create("https://docs.translab.io/tantor/errors/upload-limit-exceeded"));
    }

    @Test
    void bundleEntryLimitReturnsPayloadTooLarge() {
        UploadLimitExceededException ex = new UploadLimitExceededException("Bundle contains too many archive entries");
        ProblemDetail pd = handler.handleUploadLimit(ex);

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE.value());
        assertThat(pd.getTitle()).isEqualTo("Upload limit exceeded");
        assertThat(pd.getDetail()).isEqualTo("Bundle contains too many archive entries");
        assertThat(pd.getType()).isEqualTo(URI.create("https://docs.translab.io/tantor/errors/upload-limit-exceeded"));
    }
}
