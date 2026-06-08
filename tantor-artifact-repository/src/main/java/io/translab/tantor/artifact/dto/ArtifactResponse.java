package io.translab.tantor.artifact.dto;

import io.translab.tantor.artifact.domain.Artifact;
import io.translab.tantor.artifact.domain.ArtifactStatus;
import io.translab.tantor.artifact.domain.ServiceType;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Outward-facing view of an artifact. Excludes the on-disk path for safety. */
public record ArtifactResponse(
        UUID id,
        ServiceType serviceType,
        String name,
        String version,
        String classifier,
        String fileName,
        long fileSizeBytes,
        String contentType,
        String sha256,
        String md5,
        ArtifactStatus status,
        String description,
        String createdBy,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String downloadUrl
) {
    public static ArtifactResponse from(Artifact a) {
        return new ArtifactResponse(
                a.getId(),
                a.getServiceType(),
                a.getName(),
                a.getVersion(),
                a.getClassifier(),
                a.getFileName(),
                a.getFileSizeBytes(),
                a.getContentType(),
                a.getChecksumSha256(),
                a.getChecksumMd5(),
                a.getStatus(),
                a.getDescription(),
                a.getCreatedBy(),
                a.getCreatedAt(),
                a.getUpdatedAt(),
                "/api/v1/artifacts/" + a.getId() + "/download"
        );
    }
}
