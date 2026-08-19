package io.translab.tantor.server.service;

import io.translab.tantor.server.domain.Cluster;
import io.translab.tantor.server.domain.ClusterServiceAssignment;
import io.translab.tantor.server.domain.DiscoveryAgent;
import io.translab.tantor.server.domain.ExternalClusterNode;
import io.translab.tantor.server.domain.Host;
import io.translab.tantor.server.domain.canonical.CanonicalAgentStatus;
import io.translab.tantor.server.domain.canonical.CanonicalClusterContract;
import io.translab.tantor.server.domain.canonical.CanonicalClusterNodesResponse;
import io.translab.tantor.server.domain.canonical.CanonicalClusterType;
import io.translab.tantor.server.domain.canonical.CanonicalKafkaMode;
import io.translab.tantor.server.domain.canonical.CanonicalNodeContract;
import io.translab.tantor.server.domain.canonical.CanonicalNodeIdentity;
import io.translab.tantor.server.domain.canonical.CanonicalNodeRole;
import io.translab.tantor.server.domain.canonical.CanonicalTelemetryStatus;
import io.translab.tantor.server.repository.ClusterRepository;
import io.translab.tantor.server.repository.ClusterServiceAssignmentRepository;
import io.translab.tantor.server.repository.DiscoveryAgentRepository;
import io.translab.tantor.server.repository.ExternalClusterNodeRepository;
import io.translab.tantor.server.repository.ExternalClusterRepository;
import io.translab.tantor.server.repository.HostRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CanonicalClusterNodeResolver {

    private final ClusterRepository clusterRepository;
    private final ClusterServiceAssignmentRepository serviceAssignmentRepository;
    private final HostRepository hostRepository;
    private final ExternalClusterRepository externalClusterRepository;
    private final ExternalClusterNodeRepository externalNodeRepository;
    private final DiscoveryAgentRepository discoveryAgentRepository;
    private final HostStatusService hostStatusService;

    @Value("${tantor.discovery-agent.heartbeat-timeout-seconds:45}")
    private long discoveryAgentHeartbeatTimeoutSeconds;

    @Transactional(readOnly = true)
    public CanonicalClusterNodesResponse resolve(UUID canonicalClusterUuid) {
        Cluster cluster = clusterRepository
                .findByCanonicalClusterUuidAndStatusNot(canonicalClusterUuid, "DELETED")
                .orElseThrow(() -> new CanonicalClusterNotFoundException(
                        "No cluster exists for canonical cluster UUID " + canonicalClusterUuid));

        CanonicalClusterType type = resolveType(cluster.getOriginType());
        CanonicalKafkaMode mode = resolveMode(cluster, type);
        CanonicalClusterContract clusterContract = new CanonicalClusterContract(
                requireCanonicalUuid(cluster), cluster.getKafkaClusterId(), type, mode);

        if (clusterContract.kafkaClusterId() == null) {
            throw new CanonicalIdentityException(
                    "Kafka cluster id is not available for canonical cluster UUID " + canonicalClusterUuid);
        }

        List<CanonicalNodeContract> nodes = type == CanonicalClusterType.EXTERNAL
                ? resolveExternalNodes(clusterContract)
                : resolveInternalNodes(cluster, clusterContract);

        return new CanonicalClusterNodesResponse(clusterContract, nodes);
    }

    private List<CanonicalNodeContract> resolveInternalNodes(
            Cluster cluster,
            CanonicalClusterContract clusterContract) {
        List<CanonicalNodeContract> resolved = new ArrayList<>();
        for (ClusterServiceAssignment assignment : serviceAssignmentRepository.findByClusterId(cluster.getId())) {
            Optional<CanonicalNodeRole> role = supportedRole(assignment.getRole());
            if (role.isEmpty()) {
                continue;
            }
            if (assignment.getNodeId() == null) {
                throw new CanonicalIdentityException(
                        "Kafka service assignment " + assignment.getId() + " has no node id");
            }

            Optional<Host> host = hostRepository.findById(assignment.getHostId());
            CanonicalAgentStatus agentStatus = host
                    .map(this::internalAgentStatus)
                    .orElse(CanonicalAgentStatus.NOT_ENROLLED);
            CanonicalTelemetryStatus telemetryStatus = host
                    .map(this::internalTelemetryStatus)
                    .orElse(CanonicalTelemetryStatus.UNAVAILABLE);
            String displayHost = host.map(this::displayHost).orElse(assignment.getHostId());

            resolved.add(node(
                    clusterContract,
                    assignment.getNodeId(),
                    role.get(),
                    displayHost,
                    agentStatus,
                    telemetryStatus));
        }
        return uniqueAndSorted(resolved);
    }

    private List<CanonicalNodeContract> resolveExternalNodes(CanonicalClusterContract clusterContract) {
        List<DiscoveryAgent> boundAgents = discoveryAgentRepository.findByClusterId(clusterContract.clusterUuid());
        CanonicalAgentStatus agentStatus = externalAgentStatus(boundAgents);
        List<CanonicalNodeContract> resolved = new ArrayList<>();

        for (ExternalClusterNode externalNode
                : externalNodeRepository.findByCanonicalClusterUuid(clusterContract.clusterUuid())) {
            if (externalNode.getNodeId() == null) {
                throw new CanonicalIdentityException(
                        "External node " + externalNode.getId() + " has no node id");
            }
            Optional<CanonicalNodeRole> role = externalRole(clusterContract, externalNode);
            if (role.isEmpty()) {
                continue;
            }
            resolved.add(node(
                    clusterContract,
                    externalNode.getNodeId(),
                    role.get(),
                    externalNode.getHost(),
                    agentStatus,
                    externalTelemetryStatus(externalNode.getLastSeen())));
        }
        return uniqueAndSorted(resolved);
    }

    private CanonicalNodeContract node(
            CanonicalClusterContract cluster,
            int nodeId,
            CanonicalNodeRole role,
            String host,
            CanonicalAgentStatus agentStatus,
            CanonicalTelemetryStatus telemetryStatus) {
        CanonicalNodeIdentity identity = new CanonicalNodeIdentity(
                cluster.clusterUuid(), cluster.kafkaClusterId(), nodeId, role);
        return new CanonicalNodeContract(identity, host, agentStatus, telemetryStatus);
    }

    private List<CanonicalNodeContract> uniqueAndSorted(List<CanonicalNodeContract> nodes) {
        Map<CanonicalNodeIdentity, CanonicalNodeContract> unique = new LinkedHashMap<>();
        for (CanonicalNodeContract node : nodes) {
            if (unique.putIfAbsent(node.identity(), node) != null) {
                throw new CanonicalIdentityException("Duplicate canonical node identity " + node.identity());
            }
        }
        return unique.values().stream()
                .sorted(Comparator
                        .comparingInt((CanonicalNodeContract node) -> node.identity().nodeId())
                        .thenComparing(node -> node.identity().role().name()))
                .toList();
    }

    private UUID requireCanonicalUuid(Cluster cluster) {
        if (cluster.getCanonicalClusterUuid() == null) {
            throw new CanonicalIdentityException("Cluster " + cluster.getId() + " has no canonical cluster UUID");
        }
        return cluster.getCanonicalClusterUuid();
    }

    private CanonicalClusterType resolveType(String originType) {
        if (originType == null) {
            throw new CanonicalIdentityException("Cluster origin type is missing");
        }
        try {
            return CanonicalClusterType.valueOf(originType.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new CanonicalIdentityException("Unsupported cluster origin type " + originType);
        }
    }

    private CanonicalKafkaMode resolveMode(Cluster cluster, CanonicalClusterType type) {
        String sourceMode = cluster.getMode();
        if (type == CanonicalClusterType.EXTERNAL) {
            sourceMode = externalClusterRepository.findById(cluster.getId())
                    .map(external -> external.getKafkaMode())
                    .orElse(sourceMode);
        }
        if (sourceMode == null || sourceMode.isBlank()) {
            return CanonicalKafkaMode.UNKNOWN;
        }
        String normalized = sourceMode.trim().toUpperCase(Locale.ROOT);
        if (normalized.contains("KRAFT")) {
            return CanonicalKafkaMode.KRAFT;
        }
        if (normalized.contains("ZOOKEEPER") || normalized.equals("ZK")) {
            return CanonicalKafkaMode.ZOOKEEPER;
        }
        return CanonicalKafkaMode.UNKNOWN;
    }

    private Optional<CanonicalNodeRole> supportedRole(String role) {
        if (role == null || role.isBlank()) {
            return Optional.empty();
        }
        String normalized = role.trim().toLowerCase(Locale.ROOT)
                .replace('-', '_')
                .replace('+', '_')
                .replace(',', '_')
                .replaceAll("_+", "_");
        return switch (normalized) {
            case "broker" -> Optional.of(CanonicalNodeRole.BROKER);
            case "controller" -> Optional.of(CanonicalNodeRole.CONTROLLER);
            case "broker_controller", "controller_broker" -> Optional.of(CanonicalNodeRole.BROKER_CONTROLLER);
            default -> Optional.empty();
        };
    }

    private Optional<CanonicalNodeRole> externalRole(
            CanonicalClusterContract cluster,
            ExternalClusterNode node) {
        boolean broker = Boolean.TRUE.equals(node.getIsBroker());
        boolean controller = Boolean.TRUE.equals(node.getIsController());
        if (cluster.mode() == CanonicalKafkaMode.ZOOKEEPER) {
            return broker ? Optional.of(CanonicalNodeRole.BROKER) : Optional.empty();
        }
        if (broker && controller) {
            return Optional.of(CanonicalNodeRole.BROKER_CONTROLLER);
        }
        if (broker) {
            return Optional.of(CanonicalNodeRole.BROKER);
        }
        if (controller) {
            return Optional.of(CanonicalNodeRole.CONTROLLER);
        }
        throw new CanonicalIdentityException("External node " + node.getId() + " has no canonical Kafka role");
    }

    private CanonicalAgentStatus internalAgentStatus(Host host) {
        return "ONLINE".equalsIgnoreCase(hostStatusService.agentConnectivityStatus(host))
                ? CanonicalAgentStatus.ONLINE
                : CanonicalAgentStatus.OFFLINE;
    }

    private CanonicalTelemetryStatus internalTelemetryStatus(Host host) {
        if (host.getLastHeartbeat() == null) {
            return CanonicalTelemetryStatus.UNAVAILABLE;
        }
        return internalAgentStatus(host) == CanonicalAgentStatus.ONLINE
                ? CanonicalTelemetryStatus.LIVE
                : CanonicalTelemetryStatus.STALE;
    }

    private CanonicalAgentStatus externalAgentStatus(List<DiscoveryAgent> agents) {
        if (agents.isEmpty()) {
            return CanonicalAgentStatus.NOT_ENROLLED;
        }
        return agents.stream().anyMatch(this::isFreshOnlineDiscoveryAgent)
                ? CanonicalAgentStatus.ONLINE
                : CanonicalAgentStatus.OFFLINE;
    }

    private boolean isFreshOnlineDiscoveryAgent(DiscoveryAgent agent) {
        if (!"ONLINE".equalsIgnoreCase(agent.getStatus()) || agent.getLastHeartbeat() == null) {
            return false;
        }
        long timeout = Math.max(discoveryAgentHeartbeatTimeoutSeconds, 1);
        return agent.getLastHeartbeat().isAfter(OffsetDateTime.now().minusSeconds(timeout));
    }

    private CanonicalTelemetryStatus externalTelemetryStatus(OffsetDateTime lastSeen) {
        if (lastSeen == null) {
            return CanonicalTelemetryStatus.UNAVAILABLE;
        }
        long timeout = Math.max(discoveryAgentHeartbeatTimeoutSeconds, 1);
        return lastSeen.isAfter(OffsetDateTime.now().minusSeconds(timeout))
                ? CanonicalTelemetryStatus.LIVE
                : CanonicalTelemetryStatus.STALE;
    }

    private String displayHost(Host host) {
        if (host.getHostIp() != null && !host.getHostIp().isBlank()) {
            return host.getHostIp();
        }
        if (host.getHostname() != null && !host.getHostname().isBlank()) {
            return host.getHostname();
        }
        return host.getId();
    }
}
