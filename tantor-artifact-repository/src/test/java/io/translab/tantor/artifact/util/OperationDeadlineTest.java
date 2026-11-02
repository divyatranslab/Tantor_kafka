package io.translab.tantor.artifact.util;

import io.translab.tantor.artifact.exception.UploadLimitExceededException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OperationDeadlineTest {

    @Test
    void unlimitedDeadlineNeverExceeds() {
        OperationDeadline deadline = OperationDeadline.unlimited();
        assertThat(deadline.isUnlimited()).isTrue();
        assertThat(deadline.isExceeded()).isFalse();
        assertThat(deadline.remainingMillis()).isEqualTo(Long.MAX_VALUE);
        deadline.checkNotExceeded("test");
    }

    @Test
    void activeDeadlineReportsRemainingTime() {
        OperationDeadline deadline = OperationDeadline.start(Duration.ofSeconds(10));
        assertThat(deadline.isUnlimited()).isFalse();
        assertThat(deadline.isExceeded()).isFalse();
        assertThat(deadline.remainingMillis()).isPositive().isLessThanOrEqualTo(10_000L);
        assertThat(deadline.remainingDuration().toSeconds()).isLessThanOrEqualTo(10);
        deadline.checkNotExceeded("active");
    }

    @Test
    void expiredDeadlineThrowsUploadLimitExceededException() throws InterruptedException {
        OperationDeadline deadline = OperationDeadline.start(Duration.ofMillis(10));
        Thread.sleep(25);

        assertThat(deadline.isExceeded()).isTrue();
        assertThat(deadline.remainingMillis()).isEqualTo(0L);

        assertThatThrownBy(() -> deadline.checkNotExceeded("malware scan"))
                .isInstanceOf(UploadLimitExceededException.class)
                .hasMessageContaining("Operation timeout exceeded during malware scan");

        assertThatThrownBy(() -> deadline.remainingMillisOrThrow("staging"))
                .isInstanceOf(UploadLimitExceededException.class)
                .hasMessageContaining("Operation timeout exceeded during staging");
    }
}
