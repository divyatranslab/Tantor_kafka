package io.translab.tantor.server.domain.canonical;

import java.util.Objects;

/**
 * Canonical node data. Host and status values describe the node but are not
 * part of its identity.
 */
public record CanonicalNodeContract(
        CanonicalNodeIdentity identity,
        String host,
        CanonicalAgentStatus agentStatus,
        CanonicalTelemetryStatus telemetryStatus) {

    public CanonicalNodeContract {
        Objects.requireNonNull(identity, "identity must not be null");
        host = normalizeOptional(host);
        Objects.requireNonNull(agentStatus, "agentStatus must not be null");
        Objects.requireNonNull(telemetryStatus, "telemetryStatus must not be null");
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
