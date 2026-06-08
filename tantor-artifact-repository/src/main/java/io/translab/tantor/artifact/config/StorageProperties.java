package io.translab.tantor.artifact.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

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
}
