package io.translab.tantor.server.service;

import io.translab.tantor.server.domain.Host;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

@Service
public class HostStatusService {

    @Value("${tantor.hosts.heartbeat-timeout-seconds:90}")
    private long heartbeatTimeoutSeconds;

    public String effectiveStatus(Host host) {
        if (host == null) {
            return "OFFLINE";
        }

        String status = host.getStatus();
        if ("PENDING".equalsIgnoreCase(status)) {
            return "PENDING";
        }
        if ("UNAVAILABLE".equalsIgnoreCase(status)) {
            return "UNAVAILABLE";
        }

        OffsetDateTime lastHeartbeat = host.getLastHeartbeat();
        if (lastHeartbeat == null) {
            return "OFFLINE";
        }

        long timeoutSeconds = Math.max(heartbeatTimeoutSeconds, 1);
        if (lastHeartbeat.isBefore(OffsetDateTime.now().minusSeconds(timeoutSeconds))) {
            return "OFFLINE";
        }

        return status == null || status.isBlank() ? "ONLINE" : status;
    }

    public boolean isOnline(Host host) {
        return "ONLINE".equalsIgnoreCase(effectiveStatus(host));
    }

    public boolean isDiscoveryAgent(Host host) {
        if (host == null) {
            return false;
        }
        String version = host.getAgentVersion();
        String id = host.getId();
        return (version != null && version.toLowerCase().contains("discovery"))
                || (id != null && id.toLowerCase().startsWith("external-"));
    }

    public boolean isInfrastructureHost(Host host) {
        return !isDiscoveryAgent(host);
    }
}
