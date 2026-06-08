package io.translab.tantor.artifact.service;

import io.translab.tantor.artifact.config.StorageProperties;
import io.translab.tantor.artifact.domain.Artifact;
import io.translab.tantor.artifact.domain.ArtifactStatus;
import io.translab.tantor.artifact.domain.ServiceType;
import io.translab.tantor.artifact.dto.ChecksumResult;
import io.translab.tantor.artifact.dto.ManifestDto;
import io.translab.tantor.artifact.exception.ArtifactAlreadyExistsException;
import io.translab.tantor.artifact.exception.ChecksumMismatchException;
import io.translab.tantor.artifact.repository.ArtifactDownloadLogRepository;
import io.translab.tantor.artifact.repository.ArtifactJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ArtifactServiceTest {

    @Mock ArtifactJpaRepository repository;
    @Mock ArtifactDownloadLogRepository downloadLogRepository;
    @Mock StorageService storageService;
    @Mock ManifestService manifestService;

    StorageProperties properties;
    ArtifactService service;

    @BeforeEach
    void setUp() {
        properties = new StorageProperties();
        properties.setEnforceChecksum(true);
        service = new ArtifactService(repository, downloadLogRepository,
                storageService, manifestService, properties);

        lenient().when(storageService.relativeDir(any(), anyString(), any()))
                .thenReturn("kafka/3.7.0");
        lenient().when(manifestService.build(any(), any()))
                .thenReturn(new ManifestDto(1, ServiceType.KAFKA, "kafka", "3.7.0", null,
                        "kafka_2.13-3.7.0.tgz", 100L, "sha", "md5", "application/gzip", null, Map.of()));
        lenient().when(manifestService.toJson(any())).thenReturn("{}");
        lenient().when(repository.save(any(Artifact.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void uploadStoresAndMarksAvailable() {
        when(repository.existsByServiceTypeAndVersionAndClassifier(ServiceType.KAFKA, "3.7.0", null))
                .thenReturn(false);
        when(storageService.store(eq(ServiceType.KAFKA), eq("3.7.0"), eq(null),
                eq("kafka_2.13-3.7.0.tgz"), any(InputStream.class)))
                .thenReturn(new ChecksumResult("abc123", "md5val", 100L));

        Artifact result = service.upload(cmd(null, false), data());

        assertThat(result.getStatus()).isEqualTo(ArtifactStatus.AVAILABLE);
        assertThat(result.getChecksumSha256()).isEqualTo("abc123");
        assertThat(result.getRelativePath()).isEqualTo("kafka/3.7.0/kafka_2.13-3.7.0.tgz");
        verify(storageService).writeManifest(eq("kafka/3.7.0"), anyString());
    }

    @Test
    void uploadRejectsDuplicateWithoutOverwrite() {
        when(repository.existsByServiceTypeAndVersionAndClassifier(ServiceType.KAFKA, "3.7.0", null))
                .thenReturn(true);

        assertThatThrownBy(() -> service.upload(cmd(null, false), data()))
                .isInstanceOf(ArtifactAlreadyExistsException.class);
        verify(storageService, never()).store(any(), any(), any(), any(), any());
    }

    @Test
    void uploadFailsAndCleansUpOnChecksumMismatch() {
        when(repository.existsByServiceTypeAndVersionAndClassifier(ServiceType.KAFKA, "3.7.0", null))
                .thenReturn(false);
        when(storageService.store(any(), any(), any(), any(), any()))
                .thenReturn(new ChecksumResult("computed-real", "md5", 100L));

        assertThatThrownBy(() -> service.upload(cmd("declared-wrong", false), data()))
                .isInstanceOf(ChecksumMismatchException.class);

        verify(storageService, times(1)).deleteBinary(eq("kafka/3.7.0"), eq("kafka_2.13-3.7.0.tgz"));
        verify(repository, never()).save(any());
    }

    private ArtifactService.UploadCommand cmd(String declaredSha, boolean overwrite) {
        return new ArtifactService.UploadCommand(
                ServiceType.KAFKA, "kafka", "3.7.0", null, "kafka_2.13-3.7.0.tgz",
                "application/gzip", "test", declaredSha, Map.of(), overwrite, "tester");
    }

    private InputStream data() {
        return new ByteArrayInputStream("payload".getBytes());
    }
}
