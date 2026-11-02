package io.translab.tantor.artifact.config;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StoragePropertiesTest {

    @Test
    void defaultPropertiesAreValid() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            StorageProperties properties = new StorageProperties();
            properties.setBasePath("/tmp/tantor-test");
            assertThat(factory.getValidator().validate(properties)).isEmpty();
        }
    }

    @Test
    void validatesConcurrencyAndQueueBounds() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            StorageProperties properties = new StorageProperties();
            properties.setBasePath("/tmp/tantor-test");
            assertThat(factory.getValidator().validate(properties)).isEmpty();

            // maxConcurrentValidations must be >= 1
            properties.setMaxConcurrentValidations(0);
            assertThat(factory.getValidator().validate(properties)).isNotEmpty();
            properties.setMaxConcurrentValidations(2);
            assertThat(factory.getValidator().validate(properties)).isEmpty();

            // maxQueuedValidations must be >= 0 (0 allows immediate rejection when workers busy)
            properties.setMaxQueuedValidations(-1);
            assertThat(factory.getValidator().validate(properties)).isNotEmpty();
            properties.setMaxQueuedValidations(0);
            assertThat(factory.getValidator().validate(properties)).isEmpty();

            // validationQueueWaitTimeout must be non-null and strictly positive
            properties.setValidationQueueWaitTimeout(null);
            assertThat(factory.getValidator().validate(properties)).isNotEmpty();

            properties.setValidationQueueWaitTimeout(java.time.Duration.ZERO);
            assertThat(factory.getValidator().validate(properties)).isNotEmpty();

            properties.setValidationQueueWaitTimeout(java.time.Duration.ofSeconds(-5));
            assertThat(factory.getValidator().validate(properties)).isNotEmpty();

            properties.setValidationQueueWaitTimeout(java.time.Duration.ofSeconds(30));
            assertThat(factory.getValidator().validate(properties)).isEmpty();
        }
    }
}
