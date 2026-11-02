package io.translab.tantor.artifact.service;

import io.translab.tantor.artifact.config.StorageProperties;
import io.translab.tantor.artifact.exception.StorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StorageServiceTest {

    @TempDir
    Path tempDir;

    private StorageProperties properties;
    private ChecksumService checksumService;
    private TemporaryStorageBudget budget;
    private StorageService storageService;

    @BeforeEach
    void setUp() {
        properties = new StorageProperties();
        properties.setBasePath(tempDir.toString());
        properties.setMaxConcurrentUploads(2);
        properties.setMaxUploadBytes(10 * 1024 * 1024L);

        checksumService = new ChecksumService();
        budget = mock(TemporaryStorageBudget.class);
    }

    @Test
    void reservationAdjustFailureDeletesTemporaryFileAndUnregistersActiveUpload() {
        TemporaryStorageBudget.Reservation mockReservation = mock(TemporaryStorageBudget.Reservation.class);
        when(budget.reserve(anyLong(), any(Path.class))).thenReturn(mockReservation);
        doThrow(new IllegalStateException("Adjustment failed")).when(mockReservation).adjust(anyLong());

        storageService = new StorageService(properties, checksumService, budget);
        storageService.init();

        assertThatThrownBy(() -> storageService.storeTemporarily("kafka.tgz", new ByteArrayInputStream("payload-bytes".getBytes())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Adjustment failed");

        // Verify reservation was closed
        verify(mockReservation).close();

        // Verify temporary file is deleted from artifacts root
        try (Stream<Path> files = Files.list(storageService.artifactsRoot())) {
            List<Path> tempFiles = files.filter(p -> p.getFileName().toString().startsWith(".upload-")).toList();
            assertThat(tempFiles).isEmpty();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // Verify upload slot is released: another upload can proceed without blocking
        TemporaryStorageBudget.Reservation successfulReservation = mock(TemporaryStorageBudget.Reservation.class);
        when(budget.reserve(anyLong(), any(Path.class))).thenReturn(successfulReservation);
        StorageService.TempStoreResult res = storageService.storeTemporarily("kafka2.tgz", new ByteArrayInputStream("payload-2".getBytes()));
        assertThat(res).isNotNull();
        res.close();
        storageService.deleteTemp(res.tempPath());
    }

    @Test
    void ioExceptionDuringStreamCopyDeletesTemporaryFileAndCleansUp() {
        TemporaryStorageBudget.Reservation mockReservation = mock(TemporaryStorageBudget.Reservation.class);
        when(budget.reserve(anyLong(), any(Path.class))).thenReturn(mockReservation);

        storageService = new StorageService(properties, checksumService, budget);
        storageService.init();

        InputStream failingStream = new InputStream() {
            private int count = 0;
            @Override
            public int read() throws IOException {
                if (++count > 5) {
                    throw new IOException("Stream read failure");
                }
                return 42;
            }
        };

        assertThatThrownBy(() -> storageService.storeTemporarily("kafka-fail.tgz", failingStream))
                .isInstanceOf(StorageException.class);

        verify(mockReservation).close();

        // Verify temporary file is deleted
        try (Stream<Path> files = Files.list(storageService.artifactsRoot())) {
            List<Path> tempFiles = files.filter(p -> p.getFileName().toString().startsWith(".upload-")).toList();
            assertThat(tempFiles).isEmpty();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void successfulTemporaryStoreRegistersActiveUploadAndCloseUnregisters() throws Exception {
        TemporaryStorageBudget.Reservation mockReservation = mock(TemporaryStorageBudget.Reservation.class);
        when(budget.reserve(anyLong(), any(Path.class))).thenReturn(mockReservation);

        storageService = new StorageService(properties, checksumService, budget);
        storageService.init();

        StorageService.TempStoreResult result = storageService.storeTemporarily("kafka.tgz", new ByteArrayInputStream("valid-payload".getBytes()));
        Path tempFile = result.tempPath();

        assertThat(Files.isRegularFile(tempFile)).isTrue();
        assertThat(storageService.isActiveUpload(tempFile)).isTrue();

        result.close();
        assertThat(storageService.isActiveUpload(tempFile)).isFalse();

        storageService.deleteTemp(tempFile);
        assertThat(Files.exists(tempFile)).isFalse();
    }
}
