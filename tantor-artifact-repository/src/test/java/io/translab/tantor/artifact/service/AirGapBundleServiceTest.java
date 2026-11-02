package io.translab.tantor.artifact.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.translab.tantor.artifact.config.StorageProperties;
import io.translab.tantor.artifact.domain.Artifact;
import io.translab.tantor.artifact.domain.ArtifactStatus;
import io.translab.tantor.artifact.domain.ServiceType;
import io.translab.tantor.artifact.dto.ManifestDto;
import io.translab.tantor.artifact.exception.UploadLimitExceededException;
import io.translab.tantor.artifact.repository.ArtifactJpaRepository;
import io.translab.tantor.artifact.util.OperationDeadline;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import io.translab.tantor.artifact.exception.OperationTimeoutException;
import io.translab.tantor.artifact.util.OperationDeadline;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;

class AirGapBundleServiceTest {

    private ArtifactJpaRepository repository;
    private StorageService storageService;
    private ManifestService manifestService;
    private ArtifactService artifactService;
    private ObjectMapper objectMapper;
    private StorageProperties properties;
    private TemporaryStorageBudget storageBudget;
    private AirGapBundleService bundleService;

    @TempDir
    Path repoDir;

    @BeforeEach
    void setUp() {
        repository = mock(ArtifactJpaRepository.class);
        properties = new StorageProperties();
        properties.setBasePath(repoDir.toString());
        properties.setMaxExportEntries(5);
        properties.setMaxBundleBytes(1024L * 1024L); // 1MB

        storageBudget = new TemporaryStorageBudget(properties);
        storageService = new StorageService(properties, new ChecksumService(), storageBudget);
        storageService.init();

        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        manifestService = new ManifestService(objectMapper);
        artifactService = mock(ArtifactService.class);

        bundleService = new AirGapBundleService(
                repository, storageService, manifestService, artifactService, objectMapper, properties, storageBudget);
    }

    private Artifact createArtifact(UUID id) {
        Artifact artifact = new Artifact();
        artifact.setId(id);
        artifact.setServiceType(ServiceType.KAFKA);
        artifact.setVersion("3.7.0");
        artifact.setFileName("kafka_2.13-3.7.0.tgz");
        artifact.setRelativePath("kafka/3.7.0/kafka_2.13-3.7.0.tgz");
        artifact.setStatus(ArtifactStatus.AVAILABLE);
        artifact.setChecksumSha256("dummy-sha256");
        return artifact;
    }

    private byte[] createValidBundle() throws Exception {
        byte[] binaryContent = "test-artifact-content".getBytes(StandardCharsets.UTF_8);

        ManifestDto manifest = new ManifestDto(
                ManifestDto.CURRENT_SCHEMA_VERSION,
                ServiceType.KAFKA,
                "Kafka",
                "3.7.0",
                "2.13",
                "kafka_2.13-3.7.0.tgz",
                binaryContent.length,
                "dummy-sha256",
                null,
                "application/gzip",
                OffsetDateTime.now(),
                Map.of());

        ByteArrayOutputStream output = new ByteArrayOutputStream();

        try (GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(output);
                TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {

            addTarEntry(tar,
                    "artifacts/kafka/3.7.0/kafka_2.13-3.7.0.tgz",
                    binaryContent);

            addTarEntry(tar,
                    "artifacts/kafka/3.7.0/manifest.json",
                    objectMapper.writeValueAsBytes(manifest));
        }

        return output.toByteArray();
    }

    private void addTarEntry(
            TarArchiveOutputStream tar,
            String name,
            byte[] content) throws IOException {

        TarArchiveEntry entry = new TarArchiveEntry(name);
        entry.setSize(content.length);
        tar.putArchiveEntry(entry);
        tar.write(content);
        tar.closeArchiveEntry();
    }

    @Test
    void exportRejectsExcessiveEntries() {
        List<UUID> tooMany = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tooMany.add(UUID.randomUUID());
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThatThrownBy(() -> bundleService.export(tooMany, out, OperationDeadline.unlimited()))
                .isInstanceOf(UploadLimitExceededException.class)
                .hasMessageContaining("exceeds configured maximum of 5");
    }

    @Test
    void exportSkipsMissingBinariesWithoutFailingBundle() throws Exception {
        UUID id = UUID.randomUUID();
        Artifact a = new Artifact();
        a.setId(id);
        a.setServiceType(ServiceType.KAFKA);
        a.setVersion("3.7.0");
        a.setFileName("kafka_2.13-3.7.0.tgz");
        a.setRelativePath("kafka/3.7.0/kafka_2.13-3.7.0.tgz");
        a.setStatus(ArtifactStatus.AVAILABLE);
        a.setChecksumSha256("dummy-sha");

        when(repository.findAllById(List.of(id))).thenReturn(List.of(a));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bundleService.export(List.of(id), out, OperationDeadline.unlimited());

        // Should produce a valid tar.gz bundle even though binary was missing and
        // skipped
        assertThat(out.toByteArray().length).isPositive();
    }

    @Test
    void exportThrowsWhenEntryLimitExceeded() {
        properties.setMaxExportEntries(2);
        List<UUID> tooMany = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        assertThatThrownBy(
                () -> bundleService.export(tooMany, new ByteArrayOutputStream(), OperationDeadline.unlimited()))
                .isInstanceOf(UploadLimitExceededException.class)
                .hasMessageContaining("exceeds configured maximum");
    }

    @Test
    void exportDoesNotCloseCallerOutputStream() throws IOException {
        UUID id = UUID.randomUUID();
        Artifact a = new Artifact();
        a.setId(id);
        a.setServiceType(ServiceType.KAFKA);
        a.setVersion("3.7.0");
        a.setFileName("kafka_2.13-3.7.0.tgz");
        a.setRelativePath("kafka/3.7.0/kafka_2.13-3.7.0.tgz");
        a.setStatus(ArtifactStatus.AVAILABLE);
        a.setChecksumSha256("dummy-sha");

        when(repository.findAllById(List.of(id))).thenReturn(List.of(a));

        final boolean[] streamClosed = { false };
        ByteArrayOutputStream delegate = new ByteArrayOutputStream();
        java.io.OutputStream nonClosing = new java.io.FilterOutputStream(delegate) {
            @Override
            public void close() throws IOException {
                streamClosed[0] = true;
                super.close();
            }
        };

        bundleService.export(List.of(id), nonClosing, OperationDeadline.unlimited());

        // Underlying caller-owned stream must NOT have close() called on it
        assertThat(streamClosed[0]).isFalse();
    }

    @Test
    void importBundleExtractsUnderConfiguredFilesystemAndAdjustsReservation() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream gz = new org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream(
                baos);
                org.apache.commons.compress.archivers.tar.TarArchiveOutputStream tar = new org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(
                        gz)) {

            byte[] binaryContent = "test-binary-data-for-airgap".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            org.apache.commons.compress.archivers.tar.TarArchiveEntry binEntry = new org.apache.commons.compress.archivers.tar.TarArchiveEntry(
                    "artifacts/kafka/3.7.0/kafka_2.13-3.7.0.tgz");
            binEntry.setSize(binaryContent.length);
            tar.putArchiveEntry(binEntry);
            tar.write(binaryContent);
            tar.closeArchiveEntry();

            io.translab.tantor.artifact.dto.ManifestDto manifest = new io.translab.tantor.artifact.dto.ManifestDto(
                    io.translab.tantor.artifact.dto.ManifestDto.CURRENT_SCHEMA_VERSION,
                    ServiceType.KAFKA,
                    "Kafka",
                    "3.7.0",
                    "2.13",
                    "kafka_2.13-3.7.0.tgz",
                    binaryContent.length,
                    "dummy-sha256",
                    null,
                    "application/gzip",
                    java.time.OffsetDateTime.now(),
                    java.util.Map.of());
            byte[] manifestBytes = manifestService.toJson(manifest).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            org.apache.commons.compress.archivers.tar.TarArchiveEntry manifestEntry = new org.apache.commons.compress.archivers.tar.TarArchiveEntry(
                    "artifacts/kafka/3.7.0/manifest.json");
            manifestEntry.setSize(manifestBytes.length);
            tar.putArchiveEntry(manifestEntry);
            tar.write(manifestBytes);
            tar.closeArchiveEntry();

            tar.finish();
        }

        byte[] bundleBytes = baos.toByteArray();
        int imported = bundleService.importBundle(new java.io.ByteArrayInputStream(bundleBytes));

        assertThat(imported).isEqualTo(1);
        verify(artifactService, times(1)).upload(any(), any());

        // Budget reservation must be released after completion
        assertThat(storageBudget.getReservedBytes()).isEqualTo(0L);

        // Temp extraction directory under artifactsRoot must be deleted
        try (java.util.stream.Stream<Path> stream = Files.list(storageService.artifactsRoot())) {
            List<Path> bundleTempDirs = stream
                    .filter(p -> p.getFileName().toString().startsWith("tantor-bundle-"))
                    .toList();
            assertThat(bundleTempDirs).isEmpty();
        }
    }

    @Test
    void importBundleFailsWhenBudgetExhausted() {
        properties.setMaxAggregateTempBytes(100L); // Lower budget below maxBundleBytes (1MB)

        byte[] dummyData = new byte[] { 1, 2, 3 };
        assertThatThrownBy(() -> bundleService.importBundle(new java.io.ByteArrayInputStream(dummyData)))
                .isInstanceOf(UploadLimitExceededException.class)
                .hasMessageContaining("Temporary storage budget exhausted");

        assertThat(storageBudget.getReservedBytes()).isEqualTo(0L);
    }

    @Test
    void exportRejectsExpiredDeadline() {
        UUID id = UUID.randomUUID();
        Artifact artifact = createArtifact(id);

        when(repository.findAllById(List.of(id)))
                .thenReturn(List.of(artifact));

        OperationDeadline deadline = OperationDeadline.start(java.time.Duration.ofNanos(1));

        assertThatThrownBy(() -> bundleService.export(
                List.of(id),
                new ByteArrayOutputStream(),
                deadline)).isInstanceOf(OperationTimeoutException.class);
    }

    @Test
    void importRejectsExpiredDeadline() throws Exception {
        byte[] bundleBytes = createValidBundle();

        OperationDeadline deadline = OperationDeadline.start(java.time.Duration.ofNanos(1));

        assertThatThrownBy(() -> bundleService.importBundle(
                new java.io.ByteArrayInputStream(bundleBytes),
                deadline)).isInstanceOf(OperationTimeoutException.class);

        verify(artifactService, never()).upload(any(), any());

        assertThat(storageBudget.getReservedBytes()).isZero();
    }

    @Test
    void importPassesBundleDeadlineToUploadCommand() throws Exception {
        byte[] bundleBytes = createValidBundle();

        OperationDeadline deadline = OperationDeadline.start(java.time.Duration.ofSeconds(30));

        bundleService.importBundle(
                new java.io.ByteArrayInputStream(bundleBytes),
                deadline);

        ArgumentCaptor<ArtifactService.UploadCommand> captor = ArgumentCaptor
                .forClass(ArtifactService.UploadCommand.class);

        verify(artifactService).upload(captor.capture(), any());

        assertThat(captor.getValue().deadline()).isSameAs(deadline);
    }

}
