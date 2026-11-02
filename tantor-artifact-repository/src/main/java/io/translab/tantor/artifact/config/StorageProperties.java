package io.translab.tantor.artifact.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Strongly typed binding for the {@code tantor.repository.*} configuration.
 */
@Validated
@ConfigurationProperties(prefix = "tantor.repository")
public class StorageProperties {

    /** Filesystem root under which the {@code /artifacts} tree lives. */
    @NotBlank
    private String basePath = "/var/lib/tantor/repository";

    /** When true, a declared checksum that disagrees with the computed one fails the upload. */
    private boolean enforceChecksum = true;

    /** Buffer size for streaming file IO. */
    @Min(64 * 1024)
    private int streamBufferBytes = 1024 * 1024;

    @Min(1)
    private long maxUploadBytes = 536_870_912L;

    @Min(1)
    private long maxBundleBytes = 536_870_912L;

    @Min(1)
    private long minimumFreeSpaceBytes = 5_368_709_120L;

    @Min(1)
    private int maxConcurrentUploads = 2;

    @Min(1)
    private int maxArchiveEntries = 10_000;

    @Min(1)
    private long maxArchiveEntryBytes = 1_073_741_824L;

    @Min(1)
    private long maxArchiveExpandedBytes = 2_147_483_648L;

    @Min(1)
    private int maxArchiveCompressionRatio = 100;

    @Min(1)
    private int maxConcurrentValidations = 2;

    @Min(0)
    private int maxQueuedValidations = 10;

    @NotNull
    private Duration validationQueueWaitTimeout = Duration.ofSeconds(30);

    @Min(1)
    private long maxAggregateTempBytes = 3_221_225_472L;

    @NotNull
    private Duration uploadTimeout = Duration.ofSeconds(60);

    @NotNull
    private Duration downloadTimeout = Duration.ofSeconds(60);

    @NotNull
    private Duration bundleExportTimeout = Duration.ofSeconds(120);

    @NotNull
    private Duration bundleImportTimeout = Duration.ofSeconds(180);

    @Min(1)
    private int maxPageSize = 100;

    @Min(1)
    private int defaultPageSize = 20;

    @Min(1)
    private int maxExportEntries = 500;

    @NotNull
    private Duration staleFileThreshold = Duration.ofMinutes(30);

    @NotNull
    private Duration idempotencyRetention = Duration.ofHours(24);

    public Duration getIdempotencyRetention() {
        return idempotencyRetention;
    }

    public void setIdempotencyRetention(Duration idempotencyRetention) {
        this.idempotencyRetention = idempotencyRetention;
    }

    @AssertTrue(message = "validationQueueWaitTimeout must be non-null and strictly positive")
    public boolean isValidationQueueWaitTimeoutPositive() {
        return validationQueueWaitTimeout != null && !validationQueueWaitTimeout.isNegative() && !validationQueueWaitTimeout.isZero();
    }

    @AssertTrue(message = "operation timeouts must be non-null and strictly positive")
    public boolean areTimeoutsPositive() {
        return uploadTimeout != null && !uploadTimeout.isNegative() && !uploadTimeout.isZero()
                && downloadTimeout != null && !downloadTimeout.isNegative() && !downloadTimeout.isZero()
                && bundleExportTimeout != null && !bundleExportTimeout.isNegative() && !bundleExportTimeout.isZero()
                && bundleImportTimeout != null && !bundleImportTimeout.isNegative() && !bundleImportTimeout.isZero();
    }

    public String getBasePath() {
        return basePath;
    }

    public void setBasePath(String basePath) {
        this.basePath = basePath;
    }

    public boolean isEnforceChecksum() {
        return enforceChecksum;
    }

    public void setEnforceChecksum(boolean enforceChecksum) {
        this.enforceChecksum = enforceChecksum;
    }

    public int getStreamBufferBytes() {
        return streamBufferBytes;
    }

    public void setStreamBufferBytes(int streamBufferBytes) {
        this.streamBufferBytes = streamBufferBytes;
    }

    public long getMaxUploadBytes() { return maxUploadBytes; }
    public void setMaxUploadBytes(long maxUploadBytes) { this.maxUploadBytes = maxUploadBytes; }
    public long getMaxBundleBytes() { return maxBundleBytes; }
    public void setMaxBundleBytes(long maxBundleBytes) { this.maxBundleBytes = maxBundleBytes; }
    public long getMinimumFreeSpaceBytes() { return minimumFreeSpaceBytes; }
    public void setMinimumFreeSpaceBytes(long minimumFreeSpaceBytes) { this.minimumFreeSpaceBytes = minimumFreeSpaceBytes; }
    public int getMaxConcurrentUploads() { return maxConcurrentUploads; }
    public void setMaxConcurrentUploads(int maxConcurrentUploads) { this.maxConcurrentUploads = maxConcurrentUploads; }
    public int getMaxArchiveEntries() { return maxArchiveEntries; }
    public void setMaxArchiveEntries(int maxArchiveEntries) { this.maxArchiveEntries = maxArchiveEntries; }
    public long getMaxArchiveEntryBytes() { return maxArchiveEntryBytes; }
    public void setMaxArchiveEntryBytes(long maxArchiveEntryBytes) { this.maxArchiveEntryBytes = maxArchiveEntryBytes; }
    public long getMaxArchiveExpandedBytes() { return maxArchiveExpandedBytes; }
    public void setMaxArchiveExpandedBytes(long maxArchiveExpandedBytes) { this.maxArchiveExpandedBytes = maxArchiveExpandedBytes; }
    public int getMaxArchiveCompressionRatio() { return maxArchiveCompressionRatio; }
    public void setMaxArchiveCompressionRatio(int maxArchiveCompressionRatio) { this.maxArchiveCompressionRatio = maxArchiveCompressionRatio; }
    public int getMaxConcurrentValidations() { return maxConcurrentValidations; }
    public void setMaxConcurrentValidations(int maxConcurrentValidations) { this.maxConcurrentValidations = maxConcurrentValidations; }
    public int getMaxQueuedValidations() { return maxQueuedValidations; }
    public void setMaxQueuedValidations(int maxQueuedValidations) { this.maxQueuedValidations = maxQueuedValidations; }
    public Duration getValidationQueueWaitTimeout() { return validationQueueWaitTimeout; }
    public void setValidationQueueWaitTimeout(Duration validationQueueWaitTimeout) { this.validationQueueWaitTimeout = validationQueueWaitTimeout; }
    public long getValidationQueueWaitTimeoutMs() { return validationQueueWaitTimeout.toMillis(); }
    public void setValidationQueueWaitTimeoutMs(long ms) { this.validationQueueWaitTimeout = Duration.ofMillis(ms); }

    public long getMaxAggregateTempBytes() { return maxAggregateTempBytes; }
    public void setMaxAggregateTempBytes(long maxAggregateTempBytes) { this.maxAggregateTempBytes = maxAggregateTempBytes; }

    public Duration getUploadTimeout() { return uploadTimeout; }
    public void setUploadTimeout(Duration uploadTimeout) { this.uploadTimeout = uploadTimeout; }
    public long getUploadTimeoutMs() { return uploadTimeout.toMillis(); }
    public void setUploadTimeoutMs(long ms) { this.uploadTimeout = Duration.ofMillis(ms); }

    public Duration getDownloadTimeout() { return downloadTimeout; }
    public void setDownloadTimeout(Duration downloadTimeout) { this.downloadTimeout = downloadTimeout; }
    public long getDownloadTimeoutMs() { return downloadTimeout.toMillis(); }
    public void setDownloadTimeoutMs(long ms) { this.downloadTimeout = Duration.ofMillis(ms); }

    public Duration getBundleExportTimeout() { return bundleExportTimeout; }
    public void setBundleExportTimeout(Duration bundleExportTimeout) { this.bundleExportTimeout = bundleExportTimeout; }
    public long getBundleExportTimeoutMs() { return bundleExportTimeout.toMillis(); }
    public void setBundleExportTimeoutMs(long ms) { this.bundleExportTimeout = Duration.ofMillis(ms); }

    public Duration getBundleImportTimeout() { return bundleImportTimeout; }
    public void setBundleImportTimeout(Duration bundleImportTimeout) { this.bundleImportTimeout = bundleImportTimeout; }
    public long getBundleImportTimeoutMs() { return bundleImportTimeout.toMillis(); }
    public void setBundleImportTimeoutMs(long ms) { this.bundleImportTimeout = Duration.ofMillis(ms); }

    public int getMaxPageSize() { return maxPageSize; }
    public void setMaxPageSize(int maxPageSize) { this.maxPageSize = maxPageSize; }

    public int getDefaultPageSize() { return defaultPageSize; }
    public void setDefaultPageSize(int defaultPageSize) { this.defaultPageSize = defaultPageSize; }

    public int getMaxExportEntries() { return maxExportEntries; }
    public void setMaxExportEntries(int maxExportEntries) { this.maxExportEntries = maxExportEntries; }

    public Duration getStaleFileThreshold() { return staleFileThreshold; }
    public void setStaleFileThreshold(Duration staleFileThreshold) { this.staleFileThreshold = staleFileThreshold; }
}
