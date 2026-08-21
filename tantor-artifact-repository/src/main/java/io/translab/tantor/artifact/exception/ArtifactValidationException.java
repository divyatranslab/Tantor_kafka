package io.translab.tantor.artifact.exception;

import io.translab.tantor.artifact.domain.Artifact;

public class ArtifactValidationException extends RuntimeException {
    
    private final Artifact artifact;

    public ArtifactValidationException(Artifact artifact, String message) {
        super(message);
        this.artifact = artifact;
    }

    public ArtifactValidationException(Artifact artifact, String message, Throwable cause) {
        super(message, cause);
        this.artifact = artifact;
    }

    public Artifact getArtifact() {
        return artifact;
    }
}
