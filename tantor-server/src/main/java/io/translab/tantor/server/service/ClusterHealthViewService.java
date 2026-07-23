package io.translab.tantor.server.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.translab.tantor.server.repository.HostRepository;
import io.translab.tantor.server.repository.DiscoveryAgentRepository;
import io.translab.tantor.server.repository.ExternalClusterNodeRepository;
import java.util.*;
import java.util.stream.Collectors;
import java.time.OffsetDateTime;
import io.translab.tantor.server.domain.*;


@Service
@RequiredArgsConstructor
@Slf4j
public class ClusterHealthViewService {

    private final ObjectMapper objectMapper;
    private final BrokerMetricsCacheService brokerMetricsCacheService;
    private final KafkaAdminService kafkaAdminService;
    private final HostRepository hostRepository;
    private final HostStatusService hostStatusService;
    private final DiscoveryAgentRepository discoveryAgentRepository;
    private final ExternalClusterNodeRepository externalClusterNodeRepository;
    private final ClusterValidationService clusterValidationService;
    private final ClusterOverviewService clusterOverviewService;

    @Value("${tantor.discovery-agent.heartbeat-timeout-seconds:45}")
    private long discoveryAgentHeartbeatTimeoutSeconds;

    @Value("${tantor.external-clusters.kafka-health-timeout-seconds:5}")
    private long externalKafkaHealthTimeoutSeconds;


    public List<Map<String, Object>> mapInternalClusters(List<io.translab.tantor.server.domain.Cluster> clusters) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (io.translab.tantor.server.domain.Cluster c : clusters) {
            if ("EXTERNAL".equalsIgnoreCase(c.getMode())) {
                continue;
            }
            result.add(mapInternalCluster(c, false));
        }
        return result;
    }

    public List<Map<String, Object>> mapExternalClusters(List<io.translab.tantor.server.domain.ExternalCluster> clusters, List<io.translab.tantor.server.domain.DiscoveryAgent> discoveryAgents) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (io.translab.tantor.server.domain.ExternalCluster c : clusters) {
            List<io.translab.tantor.server.domain.ExternalClusterNode> clusterNodes = externalClusterNodeRepository.findByClusterId(c.getId());
            result.add(mapExternalCluster(c, clusterNodes, discoveryAgents, false));
        }
        return result;
    }

    public Map<String, Object> mapInternalCluster(io.translab.tantor.server.domain.Cluster c, boolean isDetail) {
        if ("EXTERNAL".equalsIgnoreCase(c.getMode())) {
            return null;
        }
        Map<String, Object> m = new HashMap<>();
        m.put("id", c.getId());
        m.put("name", c.getName());
        m.put("clusterName", c.getName());
        m.put("originType", c.getOriginType());
        m.put("installDirectory", c.getInstallDirectory());
        m.put("kafkaVersion", c.getKafkaVersion());
        m.put("mode", c.getMode());
        m.put("environment", c.getEnvironment());
        m.put("createdAt", c.getCreatedAt());
        m.put("createdBy", c.getCreatedBy());
        m.put("updatedBy", c.getUpdatedBy());
        m.put("user", c.getUser());
        m.put("role", c.getRole());
        m.put("configPath", c.getConfigPath());
        m.put("nodeIds", c.getNodeIds());
        m.put("status", c.getStatus());
        m.put("bootstrapServers", c.getBootstrapServers());
        m.put("clusterId", c.getId().toString());
        m.put("kafkaClusterId", kafkaClusterId(c));
        m.put("config", parseConfigJson(c.getConfigJson()));
        m.put("managementLevel", managementLevel(c));
        m.put("sourceLabel", sourceLabel(c));
        m.put("accessLabel", accessLabel(c));

        if (isDetail) {
            m.put("configDirectory", c.getConfigDirectory());
            m.put("dataDirectory", c.getDataDirectory());
            m.put("logDirectory", c.getLogDirectory());
        }

        List<Map<String, Object>> hosts = clusterHosts(c);
        m.put("nodeCount", hosts.isEmpty() && c.getServices() != null ? c.getServices().size() : hosts.size());
        m.put("hosts", hosts);
        m.putAll(internalRuntimeHealth(c));
        return m;
    }

    public Map<String, Object> mapExternalCluster(io.translab.tantor.server.domain.ExternalCluster c, List<io.translab.tantor.server.domain.ExternalClusterNode> clusterNodes, List<io.translab.tantor.server.domain.DiscoveryAgent> discoveryAgents, boolean isDetail) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", c.getId());
        m.put("name", c.getName());
        m.put("clusterName", c.getName());
        m.put("originType", "EXTERNAL");
        m.put("installDirectory", c.getInstallPath());
        m.put("kafkaVersion", c.getKafkaVersion());
        m.put("mode", "EXTERNAL");
        m.put("environment", c.getEnvironment());
        m.put("createdAt", c.getCreatedAt());
        m.put("createdBy", c.getCreatedBy());
        m.put("updatedBy", c.getUpdatedBy());
        m.put("nodeIds", new ArrayList<>());
        m.put("status", c.getStatus());
        m.put("bootstrapServers", c.getBootstrapServers());
        m.put("clusterId", c.getId().toString());
        m.put("kafkaClusterId", c.getKafkaClusterId());
        m.put("config", new HashMap<>());

        if (isDetail) {
            m.put("configDirectory", "");
            m.put("dataDirectory", "");
            m.put("logDirectory", c.getLogDirs());
            m.put("sourceLabel", "External");
        }

        ExternalHealthView health = externalHealthView(c, clusterNodes, discoveryAgents, isDetail);
        m.put("managementLevel", health.managementLevel());
        if (!isDetail) {
            m.put("sourceLabel", "External");
        }
        m.put("accessLabel", health.managementLevel());
        m.put("telemetry", health.telemetry());
        m.put("managedHostsCount", health.managedHostsCount());
        m.put("totalHostsCount", health.totalHostsCount());
        m.put("lastAgentHeartbeat", health.lastAgentHeartbeat() != null ? health.lastAgentHeartbeat().toString() : null);
        putExternalHealth(m, health);

        List<Map<String, Object>> hosts = externalClusterHosts(c, clusterNodes, discoveryAgents);
        m.put("nodeCount", hosts.isEmpty() ? clusterNodes.size() : hosts.size());
        m.put("hosts", hosts);
        return m;
    }

    public io.translab.tantor.server.dto.ClusterOverviewDto getExternalClusterOverview(io.translab.tantor.server.domain.ExternalCluster extCluster) {
        List<io.translab.tantor.server.domain.ExternalClusterNode> nodes = externalClusterNodeRepository.findByClusterId(extCluster.getId());
        io.translab.tantor.server.dto.ClusterOverviewDto liveOverview = null;
        try {
            liveOverview = clusterOverviewService.getOverview(extCluster.getId());
        } catch (Exception e) {
            log.warn("Live external cluster overview failed for {}: {}", extCluster.getId(), e.getMessage());
        }

        List<String> warnings = new ArrayList<>();
        if (liveOverview == null) {
            warnings.add("Live Kafka metadata is unavailable. Showing the last saved external cluster metadata.");
        }

        int brokerCount = 0;
        int activeControllerCount = 0;
        List<io.translab.tantor.server.dto.ClusterOverviewDto.BrokerRow> brokerRows = new ArrayList<>();
        List<io.translab.tantor.server.dto.ClusterOverviewDto.ControllerRow> controllerRows = new ArrayList<>();
        List<io.translab.tantor.server.dto.ClusterOverviewDto.NodePathRow> nodePathRows = new ArrayList<>();
        String overviewInstallDir = null;
        String overviewConfigDir = null;
        String overviewDataDir = null;
        String overviewLogDir = null;
        String displayVersion = externalKafkaVersion(extCluster, nodes);
        String displayControllerType = externalControllerType(extCluster, nodes);

        for (io.translab.tantor.server.domain.ExternalClusterNode node : nodes) {
            boolean isBroker = Boolean.TRUE.equals(node.getIsBroker());
            boolean isController = Boolean.TRUE.equals(node.getIsController());
            if (isBroker) brokerCount++;
            if (isController) activeControllerCount++;

            if (isBroker) {
                brokerRows.add(io.translab.tantor.server.dto.ClusterOverviewDto.BrokerRow.builder()
                        .brokerId(node.getNodeId() != null ? node.getNodeId() : -1)
                        .host(node.getHost())
                        .port(node.getPort())
                        .controller(isController)
                        .diskUsageBytes(node.getDiskUsedGb() != null ? (long)(node.getDiskUsedGb() * 1024L * 1024L * 1024L) : 0L)
                        .build());
            }
            if (isController) {
                controllerRows.add(io.translab.tantor.server.dto.ClusterOverviewDto.ControllerRow.builder()
                        .nodeId(node.getNodeId() != null ? node.getNodeId() : -1)
                        .host(node.getHost())
                        .port(node.getPort())
                        .build());
            }

            String role = (isBroker && isController) ? "broker_controller" : (isBroker ? "broker" : (isController ? "controller" : "unknown"));
            String installDir = firstNonBlank(node.getInstallDir(), extCluster.getInstallPath(), firstExternalNodeValue(nodes, node.getHost(), "installDir"));
            String configFile = firstNonBlank(node.getConfigFile(), firstExternalNodeValue(nodes, node.getHost(), "configFile"),
                    inferredExternalConfigFile(role, displayControllerType, displayVersion, installDir));
            String dataDir = firstNonBlank(node.getDataDirs(), firstExternalNodeValue(nodes, node.getHost(), "dataDirs"));
            String logDir = firstNonBlank(node.getLogDirs(), extCluster.getLogDirs(), firstExternalNodeValue(nodes, node.getHost(), "logDirs"));
            overviewInstallDir = firstNonBlank(overviewInstallDir, installDir);
            overviewConfigDir = firstNonBlank(overviewConfigDir, parentPath(configFile));
            overviewDataDir = firstNonBlank(overviewDataDir, dataDir);
            overviewLogDir = firstNonBlank(overviewLogDir, logDir);
            nodePathRows.add(io.translab.tantor.server.dto.ClusterOverviewDto.NodePathRow.builder()
                    .nodeId(node.getNodeId() != null ? node.getNodeId() : -1)
                    .host(node.getHost())
                    .role(role)
                    .installDir(installDir)
                    .config(configFile)
                    .dataDir(dataDir)
                    .logDir(logDir)
                    .hasTelemetry(node.getLastSeen() != null || firstNonBlank(installDir, configFile, dataDir, logDir) != null)
                    .build());
        }

        if (liveOverview != null) {
            liveOverview.setOriginType("EXTERNAL");
            liveOverview.setKafkaVersion(firstNonBlank(cleanExternalValue(liveOverview.getKafkaVersion()), displayVersion));
            liveOverview.setControllerType(firstNonBlank(cleanExternalValue(liveOverview.getControllerType()), displayControllerType));
            liveOverview.setInstallDirectory(firstNonBlank(liveOverview.getInstallDirectory(), overviewInstallDir));
            liveOverview.setConfigDirectory(firstNonBlank(liveOverview.getConfigDirectory(), overviewConfigDir));
            liveOverview.setDataDirectory(firstNonBlank(liveOverview.getDataDirectory(), overviewDataDir));
            liveOverview.setLogDirectory(firstNonBlank(liveOverview.getLogDirectory(), overviewLogDir));
            liveOverview.setControllers(controllerRows);
            liveOverview.setNodePaths(nodePathRows);
            if (liveOverview.getUptime() != null) {
                liveOverview.getUptime().setVersion(firstNonBlank(cleanExternalValue(liveOverview.getUptime().getVersion()), displayVersion));
                liveOverview.getUptime().setControllerType(firstNonBlank(cleanExternalValue(liveOverview.getUptime().getControllerType()), displayControllerType));
                if (liveOverview.getUptime().getActiveController() == null && activeControllerCount > 0) {
                    liveOverview.getUptime().setActiveController(1);
                }
            }
            if (!warnings.isEmpty()) {
                List<String> mergedWarnings = new ArrayList<>();
                if (liveOverview.getWarnings() != null) {
                    mergedWarnings.addAll(liveOverview.getWarnings());
                }
                for (String warning : warnings) {
                    if (!mergedWarnings.contains(warning)) {
                        mergedWarnings.add(warning);
                    }
                }
                liveOverview.setWarnings(mergedWarnings);
            }
            return liveOverview;
        }

        return io.translab.tantor.server.dto.ClusterOverviewDto.builder()
                .clusterId(extCluster.getId())
                .kafkaClusterId(extCluster.getKafkaClusterId())
                .name(extCluster.getName())
                .kafkaVersion(displayVersion)
                .controllerType(displayControllerType)
                .originType("EXTERNAL")
                .installDirectory(overviewInstallDir)
                .configDirectory(overviewConfigDir)
                .dataDirectory(overviewDataDir)
                .logDirectory(overviewLogDir)
                .generatedAt(OffsetDateTime.now())
                .warnings(warnings)
                .uptime(io.translab.tantor.server.dto.ClusterOverviewDto.UptimeSummary.builder()
                        .brokerCount(extCluster.getBrokerCount() != null ? extCluster.getBrokerCount() : brokerCount)
                        .activeController(activeControllerCount > 0 ? 1 : 0)
                        .version(displayVersion)
                        .controllerType(displayControllerType)
                        .build())
                .partitions(io.translab.tantor.server.dto.ClusterOverviewDto.PartitionSummary.builder().build())
                .brokers(brokerRows)
                .controllers(controllerRows)
                .nodePaths(nodePathRows)
                .build();
    }


    private record ExternalHealthView(
            String kafkaHealth,
            String agentHealth,
            String monitoringHealth,
            String overallHealth,
            String statusLabel,
            String reason,
            String telemetry,
            String managementLevel,
            long managedHostsCount,
            long totalHostsCount,
            OffsetDateTime lastAgentHeartbeat
    ) {}


    private String externalMetadataValue(Cluster cluster, String key) {
        if (!"EXTERNAL".equalsIgnoreCase(cluster.getMode()) || cluster.getConfigJson() == null || cluster.getConfigJson().isBlank()) {
            return null;
        }
        try {
            Map<String, Object> metadata = objectMapper.readValue(cluster.getConfigJson(), Map.class);
            Object value = metadata.get(key);
            return value == null ? null : String.valueOf(value);
        } catch (Exception e) {
            return null;
        }
    }


    private String kafkaClusterId(Cluster cluster) {
        if (cluster.getKafkaClusterId() != null && !cluster.getKafkaClusterId().isBlank()) {
            return cluster.getKafkaClusterId();
        }
        String externalId = externalMetadataValue(cluster, "kafkaClusterId");
        if (externalId != null && !externalId.isBlank()) {
            return externalId;
        }
        if ("EXTERNAL".equalsIgnoreCase(cluster.getMode())
                || !("SUCCESS".equalsIgnoreCase(cluster.getStatus()) || "ACTIVE".equalsIgnoreCase(cluster.getStatus()))) {
            return "";
        }
        return kafkaAdminService.getKafkaClusterId(cluster.getId());
    }




    private Map<String, Object> parseConfigJson(String configJson) {
        if (configJson == null || configJson.isBlank()) return new HashMap<>();
        try {
            return objectMapper.readValue(configJson, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return new HashMap<>();
        }
    }


    private String managementLevel(Cluster cluster) {
        String level = externalMetadataValue(cluster, "managementMode");
        if (level != null && !level.isBlank()) {
            return level;
        }
        if ("EXTERNAL".equalsIgnoreCase(cluster.getMode())) {
            return "BOOTSTRAP_ONLY";
        }
        return "INTERNAL_MANAGED";
    }


    private String sourceLabel(Cluster cluster) {
        return "EXTERNAL".equalsIgnoreCase(cluster.getMode()) ? "External" : "Internal";
    }


    private String accessLabel(Cluster cluster) {
        if (!"EXTERNAL".equalsIgnoreCase(cluster.getMode())) {
            return "Full access";
        }
        return "AGENT_MANAGED".equalsIgnoreCase(managementLevel(cluster))
                ? "Fully managed"
                : "Metadata available";
    }


    private Map<String, Object> internalRuntimeHealth(Cluster cluster) {
        Map<String, Object> health = new LinkedHashMap<>();
        String deploymentStatus = cluster.getStatus() == null ? "" : cluster.getStatus().trim();

        if (!"SUCCESS".equalsIgnoreCase(deploymentStatus)) {
            health.put("runtimeHealth", deploymentStatus.isBlank() ? "UNKNOWN" : deploymentStatus.toUpperCase());
            health.put("runtimeStatusLabel", deploymentStatus.isBlank() ? "Unknown" : deploymentStatus);
            health.put("runtimeStatusReason", "Deployment is not in SUCCESS state yet.");
            return health;
        }

        try {
            List<io.translab.tantor.server.dto.BrokerSummaryDto> brokers = brokerMetricsCacheService.getBrokerSummaries(cluster);
            long total = brokers.size();
            long offline = brokers.stream().filter(b -> "OFFLINE".equalsIgnoreCase(b.getBrokerHealth())).count();
            long degraded = brokers.stream().filter(b -> "DEGRADED".equalsIgnoreCase(b.getBrokerHealth())).count();
            long healthy = brokers.stream().filter(b -> "HEALTHY".equalsIgnoreCase(b.getBrokerHealth())).count();

            health.put("runtimeBrokerCount", total);
            health.put("runtimeHealthyBrokers", healthy);
            health.put("runtimeDegradedBrokers", degraded);
            health.put("runtimeOfflineBrokers", offline);

            if (total == 0) {
                health.put("runtimeHealth", "UNKNOWN");
                health.put("runtimeStatusLabel", "Unknown");
                health.put("runtimeStatusReason", "No broker services are mapped for runtime verification.");
            } else if (offline == total) {
                health.put("runtimeHealth", "OFFLINE");
                health.put("runtimeStatusLabel", "Kafka Offline");
                health.put("runtimeStatusReason", "All broker agents or Kafka endpoints are unavailable.");
            } else if (offline > 0) {
                health.put("runtimeHealth", "DEGRADED");
                health.put("runtimeStatusLabel", "Degraded");
                health.put("runtimeStatusReason", offline + " of " + total + " broker node(s) are offline.");
            } else if (degraded > 0) {
                health.put("runtimeHealth", "DEGRADED");
                health.put("runtimeStatusLabel", "Kafka Check Failed");
                health.put("runtimeStatusReason", "Host agents are online, but Kafka/JMX verification failed for " + degraded + " broker node(s).");
            } else {
                health.put("runtimeHealth", "HEALTHY");
                health.put("runtimeStatusLabel", "Active");
                health.put("runtimeStatusReason", "Deployment succeeded and broker runtime checks are healthy.");
            }
        } catch (Exception e) {
            health.put("runtimeHealth", "DEGRADED");
            health.put("runtimeStatusLabel", "Kafka Check Failed");
            health.put("runtimeStatusReason", "Runtime verification failed: " + e.getMessage());
        }

        return health;
    }


    private ExternalHealthView externalHealthView(
            ExternalCluster cluster,
            List<io.translab.tantor.server.domain.ExternalClusterNode> nodes,
            List<io.translab.tantor.server.domain.DiscoveryAgent> knownDiscoveryAgents,
            boolean liveKafkaCheck
    ) {
        return externalHealthView(cluster, nodes, knownDiscoveryAgents, liveKafkaCheck, null);
    }


    private ExternalHealthView externalHealthView(
            ExternalCluster cluster,
            List<io.translab.tantor.server.domain.ExternalClusterNode> nodes,
            List<io.translab.tantor.server.domain.DiscoveryAgent> knownDiscoveryAgents,
            boolean liveKafkaCheck,
            String kafkaHealthOverride
    ) {
        List<io.translab.tantor.server.domain.ExternalClusterNode> safeNodes = nodes == null ? List.of() : nodes;
        List<String> hosts = safeNodes.stream()
                .map(io.translab.tantor.server.domain.ExternalClusterNode::getHost)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
        long totalHostsCount = hosts.size();

        List<io.translab.tantor.server.domain.DiscoveryAgent> allAgents =
                knownDiscoveryAgents == null ? discoveryAgentRepository.findAll() : knownDiscoveryAgents;
        List<io.translab.tantor.server.domain.DiscoveryAgent> linkedAgents = allAgents.stream()
                .filter(agent -> cluster.getId().equals(agent.getClusterId()))
                .toList();
        OffsetDateTime maxHeartbeat = null;
        long reportingHostsCount = 0;
        long freshHostsCount = 0;
        for (String host : hosts) {
            Optional<io.translab.tantor.server.domain.DiscoveryAgent> freshAgent = matchingFreshAgent(host, cluster.getId(), linkedAgents, allAgents);
            if (freshAgent.isPresent()) {
                freshHostsCount++;
            }
            Optional<io.translab.tantor.server.domain.DiscoveryAgent> lastReportingAgent =
                    freshAgent.isPresent() ? freshAgent : matchingAgent(host, cluster.getId(), linkedAgents, allAgents);
            if (lastReportingAgent.isPresent()) {
                reportingHostsCount++;
                OffsetDateTime heartbeat = lastReportingAgent.get().getLastHeartbeat();
                if (heartbeat != null && (maxHeartbeat == null || heartbeat.isAfter(maxHeartbeat))) {
                    maxHeartbeat = heartbeat;
                }
            }
        }

        String telemetry = "None";
        String managementLevel = "Agent Not Connected";
        String agentHealth = reportingHostsCount > 0 ? "NOT_CONNECTED" : "NOT_INSTALLED";
        if (reportingHostsCount > 0) {
            if (reportingHostsCount == totalHostsCount || totalHostsCount == 0) {
                telemetry = "Full";
                managementLevel = "Agent Connected";
            } else {
                telemetry = "Partial";
                managementLevel = "Partially Connected";
            }
        }
        if (freshHostsCount > 0) {
            if (freshHostsCount == totalHostsCount || totalHostsCount == 0) {
                agentHealth = "CONNECTED";
            } else {
                agentHealth = "PARTIAL";
            }
        }

        String kafkaHealth = kafkaHealthOverride != null
                ? kafkaHealthOverride
                : externalKafkaHealth(cluster, safeNodes, liveKafkaCheck);
        String overallHealth = "OFFLINE".equals(kafkaHealth) ? "OFFLINE"
                : "DEGRADED".equals(kafkaHealth) ? "DEGRADED"
                : "UNKNOWN".equals(kafkaHealth) ? "UNKNOWN"
                : "HEALTHY";
        String statusLabel = switch (overallHealth) {
            case "OFFLINE" -> "Kafka Offline";
            case "DEGRADED" -> "Kafka Degraded";
            case "HEALTHY" -> "Kafka Online";
            default -> "Unknown";
        };
        String reason = externalHealthReason(kafkaHealth, agentHealth, freshHostsCount, totalHostsCount);

        return new ExternalHealthView(
                kafkaHealth,
                agentHealth,
                "UNKNOWN",
                overallHealth,
                statusLabel,
                reason,
                telemetry,
                managementLevel,
                freshHostsCount,
                totalHostsCount,
                maxHeartbeat
        );
    }


    private Optional<io.translab.tantor.server.domain.DiscoveryAgent> matchingAgent(
            String host,
            UUID clusterId,
            List<io.translab.tantor.server.domain.DiscoveryAgent> linkedAgents,
            List<io.translab.tantor.server.domain.DiscoveryAgent> allAgents
    ) {
        return linkedAgents.stream()
                .filter(agent -> matchesDiscoveryAgent(agent, host))
                .findFirst()
                .or(() -> allAgents.stream()
                        .filter(agent -> matchesDiscoveryAgent(agent, host))
                        .filter(agent -> agent.getClusterId() == null || agent.getClusterId().equals(clusterId))
                        .findFirst());
    }


    private Optional<io.translab.tantor.server.domain.DiscoveryAgent> matchingFreshAgent(
            String host,
            UUID clusterId,
            List<io.translab.tantor.server.domain.DiscoveryAgent> linkedAgents,
            List<io.translab.tantor.server.domain.DiscoveryAgent> allAgents
    ) {
        return linkedAgents.stream()
                .filter(agent -> isFreshOnlineAgent(agent) && matchesDiscoveryAgent(agent, host))
                .findFirst()
                .or(() -> allAgents.stream()
                        .filter(agent -> isFreshOnlineAgent(agent) && matchesDiscoveryAgent(agent, host))
                        .filter(agent -> agent.getClusterId() == null || agent.getClusterId().equals(clusterId))
                        .findFirst());
    }


    private boolean isFreshOnlineAgent(io.translab.tantor.server.domain.DiscoveryAgent agent) {
        return agent != null
                && "ONLINE".equalsIgnoreCase(agent.getStatus())
                && agent.getLastHeartbeat() != null
                && agent.getLastHeartbeat().isAfter(OffsetDateTime.now().minusSeconds(discoveryAgentFreshnessSeconds()));
    }


    private String externalKafkaHealth(
            ExternalCluster cluster,
            List<io.translab.tantor.server.domain.ExternalClusterNode> nodes,
            boolean liveKafkaCheck
    ) {
        if (cluster.getBootstrapServers() == null || cluster.getBootstrapServers().isBlank()) {
            return "UNKNOWN";
        }
        if (!liveKafkaCheck) {
            if ("FAILED".equalsIgnoreCase(cluster.getStatus()) || Boolean.FALSE.equals(cluster.getIsRunning())) {
                return "OFFLINE";
            }
            if ("SUCCESS".equalsIgnoreCase(cluster.getStatus())
                    || "DEGRADED".equalsIgnoreCase(cluster.getStatus())
                    || (cluster.getKafkaClusterId() != null && !cluster.getKafkaClusterId().isBlank())
                    || (cluster.getBrokerCount() != null && cluster.getBrokerCount() > 0)
                    || !nodes.isEmpty()) {
                return "HEALTHY";
            }
            return "UNKNOWN";
        }
        return kafkaAdminService.isClusterReachable(cluster.getId(), externalKafkaHealthTimeoutSeconds)
                ? "HEALTHY"
                : "OFFLINE";
    }


    private String externalHealthReason(String kafkaHealth, String agentHealth, long managedHostsCount, long totalHostsCount) {
        if ("OFFLINE".equals(kafkaHealth)) {
            return "Kafka bootstrap/admin connectivity is unavailable.";
        }
        if ("DEGRADED".equals(kafkaHealth)) {
            return "Kafka is reachable but reports a degraded runtime state.";
        }
        if ("CONNECTED".equals(agentHealth)) {
            return "Kafka is reachable and discovery agent heartbeat is fresh.";
        }
        if ("PARTIAL".equals(agentHealth)) {
            return "Kafka is reachable; discovery agent is fresh for " + managedHostsCount + " of " + totalHostsCount + " host(s).";
        }
        if ("NOT_INSTALLED".equals(agentHealth)) {
            return "Kafka is reachable; no discovery agent has reported for this cluster.";
        }
        return "Kafka is reachable; discovery agent heartbeat is missing or stale.";
    }


    private long discoveryAgentFreshnessSeconds() {
        return Math.max(15, discoveryAgentHeartbeatTimeoutSeconds);
    }


    private void putExternalHealth(Map<String, Object> target, ExternalHealthView health) {
        target.put("kafkaHealth", health.kafkaHealth());
        target.put("agentHealth", health.agentHealth());
        target.put("monitoringHealth", health.monitoringHealth());
        target.put("overallHealth", health.overallHealth());
        target.put("runtimeHealth", health.overallHealth());
        target.put("runtimeStatusLabel", health.statusLabel());
        target.put("runtimeStatusReason", health.reason());
    }






    private boolean matchesDiscoveryAgent(io.translab.tantor.server.domain.DiscoveryAgent agent, String host) {
        if (agent == null || host == null || host.isBlank()) {
            return false;
        }
        if (agent.getHostname() != null && agent.getHostname().equalsIgnoreCase(host)) {
            return true;
        }
        return parseAgentAddresses(agent.getIpAddresses()).stream()
                .anyMatch(address -> address.equalsIgnoreCase(host));
    }


    private List<String> parseAgentAddresses(String ipAddresses) {
        if (ipAddresses == null || ipAddresses.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(ipAddresses, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
        } catch (Exception ignored) {
            List<String> values = new ArrayList<>();
            for (String part : ipAddresses.replaceAll("\\[|\\]|\\\"", "").split(",")) {
                String value = part.trim();
                if (!value.isBlank()) {
                    values.add(value);
                }
            }
            return values;
        }
    }


    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }


    private String firstExternalNodeValue(
            List<io.translab.tantor.server.domain.ExternalClusterNode> nodes,
            String host,
            String field
    ) {
        if (nodes == null || host == null || host.isBlank()) {
            return null;
        }
        for (io.translab.tantor.server.domain.ExternalClusterNode node : nodes) {
            if (node == null || node.getHost() == null || !node.getHost().equalsIgnoreCase(host)) {
                continue;
            }
            String value = switch (field) {
                case "installDir" -> node.getInstallDir();
                case "configFile" -> node.getConfigFile();
                case "dataDirs" -> node.getDataDirs();
                case "logDirs" -> node.getLogDirs();
                default -> null;
            };
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }


    private String externalKafkaVersion(
            io.translab.tantor.server.domain.ExternalCluster cluster,
            List<io.translab.tantor.server.domain.ExternalClusterNode> nodes
    ) {
        String version = cleanExternalValue(cluster.getKafkaVersion());
        if (version != null) {
            return version;
        }
        version = versionFromKafkaPath(cleanExternalValue(cluster.getInstallPath()));
        if (version != null) {
            return version;
        }
        for (io.translab.tantor.server.domain.ExternalClusterNode node : nodes) {
            version = versionFromKafkaPath(cleanExternalValue(node.getInstallDir()));
            if (version != null) {
                return version;
            }
        }
        return "Not reported";
    }


    private String externalControllerType(
            io.translab.tantor.server.domain.ExternalCluster cluster,
            List<io.translab.tantor.server.domain.ExternalClusterNode> nodes
    ) {
        String mode = cleanExternalValue(cluster.getKafkaMode());
        if (mode != null) {
            return "zookeeper".equalsIgnoreCase(mode) ? "ZooKeeper" : "KRaft";
        }
        boolean hasController = nodes.stream().anyMatch(node -> Boolean.TRUE.equals(node.getIsController()));
        return hasController ? "KRaft" : "Not reported";
    }


    private String cleanExternalValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if ("null".equalsIgnoreCase(trimmed)
                || "unknown".equalsIgnoreCase(trimmed)
                || "auto-detected".equalsIgnoreCase(trimmed)
                || "auto-detected by Kafka client".equalsIgnoreCase(trimmed)) {
            return null;
        }
        return trimmed;
    }


    private String versionFromKafkaPath(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String normalized = path.replace('\\', '/');
        String[] segments = normalized.split("/");
        for (int i = segments.length - 1; i >= 0; i--) {
            String segment = segments[i];
            int index = segment.lastIndexOf('-');
            if (index < 0 || index == segment.length() - 1) {
                continue;
            }
            String candidate = segment.substring(index + 1);
            if (candidate.matches("\\d+(\\.\\d+){1,3}")) {
                return candidate;
            }
        }
        return null;
    }


    private String inferredExternalConfigFile(String role, String kafkaMode, String kafkaVersion, String installDir) {
        if (installDir == null || installDir.isBlank()) {
            return null;
        }
        if (!"KRaft".equalsIgnoreCase(kafkaMode)) {
            return installDir + "/config/server.properties";
        }
        boolean kafka4OrLater = clusterValidationService.parseKafkaVersion(kafkaVersion)[0] >= 4;
        String configRoot = kafka4OrLater ? installDir + "/config" : installDir + "/config/kraft";
        if ("controller".equalsIgnoreCase(role)) {
            return configRoot + "/controller.properties";
        }
        if ("broker".equalsIgnoreCase(role)) {
            return configRoot + "/broker.properties";
        }
        return configRoot + "/server.properties";
    }


    private String parentPath(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        int index = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return index > 0 ? path.substring(0, index) : null;
    }


    private List<Map<String, Object>> clusterHosts(Cluster cluster) {
        List<Map<String, Object>> hosts = new ArrayList<>();
        if (cluster.getServices() != null) {
            for (ClusterServiceAssignment service : cluster.getServices()) {
                hostRepository.findById(service.getHostId()).ifPresent(host -> {
                    if (!"EXTERNAL".equalsIgnoreCase(cluster.getMode())
                            || "ONLINE".equalsIgnoreCase(hostStatusService.effectiveStatus(host))) {
                        hosts.add(hostSummary(service, host));
                    }
                });
            }
        }
        return hosts;
    }


    private Map<String, Object> hostSummary(ClusterServiceAssignment service, io.translab.tantor.server.domain.Host host) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("hostId", host.getId());
        summary.put("hostname", host.getHostname());
        summary.put("ipAddress", firstIp(host.getIpAddresses()));
        summary.put("status", hostStatusService.effectiveStatus(host));
        summary.put("role", service.getRole());
        summary.put("nodeId", service.getNodeId());
        summary.put("lastHeartbeat", host.getLastHeartbeat());
        summary.put("diskUsedGb", host.getDiskUsedGb());
        summary.put("diskTotalGb", host.getDiskTotalGb());
        summary.put("bootstrap", "");
        return summary;
    }


    private List<Map<String, Object>> externalClusterHosts(
            ExternalCluster cluster,
            List<io.translab.tantor.server.domain.ExternalClusterNode> nodes,
            List<io.translab.tantor.server.domain.DiscoveryAgent> allAgents
    ) {
        List<Map<String, Object>> hosts = new ArrayList<>();
        List<io.translab.tantor.server.domain.DiscoveryAgent> safeAgents = allAgents == null ? java.util.List.of() : allAgents;
        List<io.translab.tantor.server.domain.DiscoveryAgent> agents = safeAgents.stream()
                .filter(a -> cluster.getId().equals(a.getClusterId()))
                .collect(java.util.stream.Collectors.toList());
        OffsetDateTime now = OffsetDateTime.now();

        for (io.translab.tantor.server.domain.ExternalClusterNode node : nodes) {
            Optional<io.translab.tantor.server.domain.DiscoveryAgent> agentMatch = agents.stream()
                    .filter(agent -> isFreshOnlineAgent(agent) && matchesDiscoveryAgent(agent, node.getHost()))
                    .findFirst()
                    .or(() -> safeAgents.stream()
                            .filter(agent -> isFreshOnlineAgent(agent) && matchesDiscoveryAgent(agent, node.getHost()))
                            .filter(agent -> agent.getClusterId() == null || agent.getClusterId().equals(cluster.getId()))
                            .findFirst());

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("id", node.getId().toString());
            summary.put("hostId", "");
            summary.put("nodeId", node.getNodeId());
            summary.put("hostname", agentMatch.map(io.translab.tantor.server.domain.DiscoveryAgent::getHostname)
                    .filter(value -> value != null && !value.isBlank())
                    .orElse(node.getHost()));
            summary.put("ipAddress", node.getHost());
            summary.put("role", externalNodeRole(node));
            summary.put("status", agentMatch.isPresent() ? "Managed" : "Bootstrap connected");
            summary.put("lastHeartbeat", agentMatch
                    .map(io.translab.tantor.server.domain.DiscoveryAgent::getLastHeartbeat)
                    .orElse(node.getLastSeen()));
            summary.put("diskUsedGb", node.getDiskUsedGb());
            summary.put("diskTotalGb", node.getDiskTotalGb());
            summary.put("bootstrap", cluster.getBootstrapServers());

            if (agentMatch.isEmpty()) {
                safeAgents.stream()
                        .filter(this::isFreshOnlineAgent)
                        .filter(agent -> agent.getLastHeartbeat() != null
                                && java.time.Duration.between(agent.getLastHeartbeat(), now).getSeconds() <= discoveryAgentFreshnessSeconds())
                        .filter(agent -> matchesDiscoveryAgent(agent, node.getHost()))
                        .filter(agent -> agent.getClusterId() == null || !agent.getClusterId().equals(cluster.getId()))
                        .findFirst()
                        .ifPresent(agent -> {
                            summary.put("agentAvailable", true);
                            summary.put("availableAgentId", agent.getId().toString());
                        });
            }

            hosts.add(summary);
        }
        return hosts;
    }


    private String externalNodeRole(io.translab.tantor.server.domain.ExternalClusterNode node) {
        boolean isBroker = Boolean.TRUE.equals(node.getIsBroker());
        boolean isController = Boolean.TRUE.equals(node.getIsController());
        if (isBroker && isController) return "broker_controller";
        if (isBroker) return "broker";
        if (isController) return "controller";
        return "unknown";
    }






    private String firstIp(String ipAddresses) {
        if (ipAddresses == null || ipAddresses.isBlank() || "[]".equals(ipAddresses)) {
            return "";
        }
        try {
            List<String> ips = objectMapper.readValue(ipAddresses, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
            return ips.isEmpty() ? "" : ips.get(0);
        } catch (Exception ignored) {
            return ipAddresses.replaceAll("\\[|\\]|\\\"", "").split(",")[0].trim();
        }
    }



}
