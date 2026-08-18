package io.translab.tantor.server.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.translab.tantor.server.domain.Cluster;
import io.translab.tantor.server.domain.ClusterServiceAssignment;
import io.translab.tantor.server.domain.ExternalCluster;
import io.translab.tantor.server.domain.ExternalClusterNode;
import io.translab.tantor.server.domain.Host;
import io.translab.tantor.server.repository.ClusterRepository;
import io.translab.tantor.server.repository.ExternalClusterRepository;
import io.translab.tantor.server.repository.ExternalClusterNodeRepository;
import io.translab.tantor.server.repository.HostRepository;
import io.translab.tantor.server.security.EncryptionService;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;

@Service
@RequiredArgsConstructor
@Slf4j
public class PrometheusMonitoringService {

    private static final int DEFAULT_JMX_PORT = 7071;
    private static final int DEFAULT_NODE_EXPORTER_PORT = 9100;

    private final ClusterRepository clusterRepository;
    private final ExternalClusterRepository externalClusterRepository;
    private final ExternalClusterNodeRepository externalClusterNodeRepository;
    private final HostRepository hostRepository;
    private final EncryptionService encryptionService;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${tantor.monitoring.mode:direct}")
    private String monitoringMode;

    @Value("${tantor.monitoring.prometheus-url:}")
    private String prometheusUrl;

    @Value("${tantor.monitoring.grafana-url:}")
    private String grafanaUrl;

    @Value("${tantor.monitoring.grafana-datasource-uid:}")
    private String grafanaDatasourceUid;

    @Value("${tantor.monitoring.grafana-username:}")
    private String grafanaUsername;

    @Value("${tantor.monitoring.grafana-password:}")
    private String grafanaPassword;

    @Value("${tantor.monitoring.grafana-skip-tls-validation:false}")
    private boolean grafanaSkipTlsValidation;

    @Value("${tantor.monitoring.exporter-host:}")
    private String defaultExporterHost;

    @Value("${tantor.monitoring.kafka-exporter-port-base:9308}")
    private int kafkaExporterPortBase;

    @Value("${tantor.monitoring.jmx-exporter-port:7071}")
    private int defaultJmxExporterPort;

    @PostConstruct
    void configureGrafanaTls() {
        if (!grafanaSkipTlsValidation) {
            return;
        }
        try {
            TrustManager[] trustAllManagers = new TrustManager[] {
                    new X509TrustManager() {
                        @Override
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }
                    }
            };
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllManagers, new SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.getSocketFactory());
            HostnameVerifier trustAllHosts = (hostname, session) -> true;
            HttpsURLConnection.setDefaultHostnameVerifier(trustAllHosts);
            log.warn("Grafana TLS validation is disabled for monitoring proxy requests. Use only for test/self-signed environments.");
        } catch (Exception e) {
            log.warn("Could not disable Grafana TLS validation", e);
        }
    }



    @Transactional(readOnly = true)
    public List<SdTargetGroup> prometheusTargets() {
        List<SdTargetGroup> targets = new ArrayList<>();
        for (Cluster cluster : clusterRepository.findByStatusNot("DELETED")) {
            if (!Boolean.TRUE.equals(cluster.getMonitoringEnabled()) && !isExternal(cluster)) {
                continue;
            }

            if (isExternal(cluster)) {
                addExternalJmxTargets(targets, cluster);
            } else {
                addInternalTargets(targets, cluster);
            }
        }
        for (ExternalCluster extCluster : externalClusterRepository.findByStatusNot("DELETED")) {
            addExternalJmxTargets(targets, extCluster);
        }
        return targets;
    }

    @Transactional(readOnly = true)
    public List<MonitoringClusterSummary> clusters(String type) {
        String normalizedType = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
        Map<UUID, MonitoringClusterSummary> result = new LinkedHashMap<>();
        for (Cluster cluster : clusterRepository.findByStatusNot("DELETED")) {
            if (!normalizedType.isBlank() && !normalizedType.equals(origin(cluster))) {
                continue;
            }
            MonitoringClusterSummary summary = new MonitoringClusterSummary();
            summary.setId(cluster.getId());
            summary.setName(cluster.getName());
            summary.setOriginType(origin(cluster));
            summary.setMonitoringEnabled(Boolean.TRUE.equals(cluster.getMonitoringEnabled()));
            summary.setKafkaExporterTarget(exporterTarget(cluster).orElse(null));
            summary.setJmxAvailable(hasJmxTargets(cluster));
            summary.setWarning(monitoringWarning(cluster));
            summary.setNodes(monitoringNodes(cluster));
            result.put(cluster.getId(), summary);
        }
        for (ExternalCluster cluster : externalClusterRepository.findByStatusNot("DELETED")) {
            if (!normalizedType.isBlank() && !normalizedType.equals("EXTERNAL")) {
                continue;
            }
            MonitoringClusterSummary summary = new MonitoringClusterSummary();
            summary.setId(cluster.getId());
            summary.setName(cluster.getName());
            summary.setOriginType("EXTERNAL");
            summary.setMonitoringEnabled(true);
            summary.setKafkaExporterTarget(null);
            summary.setJmxAvailable(hasJmxTargets(cluster));
            summary.setWarning(null);
            summary.setNodes(monitoringNodes(cluster));
            // External clusters are mirrored into kf_clusters for the shared cluster APIs.
            // Keep the richer mirrored entry when both repositories contain the same id.
            result.putIfAbsent(cluster.getId(), summary);
        }
        return new ArrayList<>(result.values());
    }

    @Transactional(readOnly = true)
    public MonitoringOverview overview(UUID clusterId) {
        return overview(clusterId, null);
    }

    @Transactional(readOnly = true)
    public MonitoringOverview overview(UUID clusterId, String nodeId) {
        Cluster cluster = clusterRepository.findWithServicesById(clusterId)
                .orElseThrow(() -> new IllegalArgumentException("Cluster not found"));

        List<MonitoringNodeSummary> nodes = monitoringNodes(cluster);
        String selectedNodeId = selectedNodeId(nodes, nodeId);
        String clusterSelector = labelSelector(cluster.getId());
        String metricSelector = labelSelector(cluster.getId(), selectedNodeId);

        MonitoringOverview overview = new MonitoringOverview();
        overview.setClusterId(cluster.getId());
        overview.setName(cluster.getName());
        overview.setOriginType(origin(cluster));
        overview.setPrometheusUrl(monitoringSourceUrl());
        overview.setKafkaExporterTarget(exporterTarget(cluster).orElse(null));
        overview.setJmxAvailable(hasJmxTargets(cluster));
        overview.setNodes(nodes);
        overview.setSelectedNodeId(selectedNodeId);
        overview.setWarnings(new ArrayList<>());
        String warning = monitoringWarning(cluster);
        if (warning != null) {
            overview.getWarnings().add(warning);
        }

        overview.setKafkaExporterUp(firstNumber("max(max_over_time(up{job=\"kafka_exporter\"," + clusterSelector + "}[90s]))"));
        overview.setJmxUp(firstNumber("max(max_over_time(up{job=\"kafka_jmx\"," + metricSelector + "}[90s]))"));
        overview.setBrokerCount(firstNumber("max(kafka_brokers{" + clusterSelector + "})"));
        overview.setTopicCount(firstNumber("count(count by (topic) (kafka_topic_partitions{" + clusterSelector + "}))"));
        overview.setPartitionCount(firstNumber("sum(max(kafka_topic_partitions{" + clusterSelector + "}) by (topic))"));
        overview.setUnderReplicatedPartitions(firstOrZero("sum(kafka_topic_partition_under_replicated_partition{" + clusterSelector + "})"));
        overview.setConsumerLag(firstOrZero("sum(max(kafka_consumergroup_lag{" + clusterSelector + "}) by (consumergroup, topic))"));
        overview.setMessagesInPerSecond(firstPresentNumber(
                append(brokerTopicRate(metricSelector, "MessagesInPerSec"),
                        "sum(rate(kafka_topic_partition_current_offset{" + metricSelector + "}[5m]))")
        ));
        overview.setBytesInPerSecond(firstPresentNumber(brokerTopicRate(metricSelector, "BytesInPerSec")));
        overview.setBytesOutPerSecond(firstPresentNumber(brokerTopicRate(metricSelector, "BytesOutPerSec")));
        overview.setJvmHeapUsedPercent(firstPresentNumber(
                heapPercent(metricSelector, "jvm_memory_bytes_used", "jvm_memory_bytes_max", "heap"),
                heapPercent(metricSelector, "jvm_memory_bytes_used", "jvm_memory_bytes_committed", "heap"),
                heapPercent(metricSelector, "jvm_memory_used_bytes", "jvm_memory_max_bytes", "heap"),
                heapPercent(metricSelector, "jvm_memory_used_bytes", "jvm_memory_committed_bytes", "heap"),
                heapPercent(metricSelector, "jvm_memory_bytes_used", "jvm_memory_bytes_max", "Heap"),
                heapPercent(metricSelector, "jvm_memory_bytes_used", "jvm_memory_bytes_committed", "Heap"),
                heapPercent(metricSelector, "jvm_memory_used_bytes", "jvm_memory_max_bytes", "Heap"),
                heapPercent(metricSelector, "jvm_memory_used_bytes", "jvm_memory_committed_bytes", "Heap")
        ));
        overview.setJvmHeapAvailableBytes(firstPresentNumber(
                heapAvailableBytes(metricSelector, "jvm_memory_bytes_used", "jvm_memory_bytes_max", "heap"),
                heapAvailableBytes(metricSelector, "jvm_memory_bytes_used", "jvm_memory_bytes_committed", "heap"),
                heapAvailableBytes(metricSelector, "jvm_memory_used_bytes", "jvm_memory_max_bytes", "heap"),
                heapAvailableBytes(metricSelector, "jvm_memory_used_bytes", "jvm_memory_committed_bytes", "heap"),
                heapAvailableBytes(metricSelector, "jvm_memory_bytes_used", "jvm_memory_bytes_max", "Heap"),
                heapAvailableBytes(metricSelector, "jvm_memory_bytes_used", "jvm_memory_bytes_committed", "Heap"),
                heapAvailableBytes(metricSelector, "jvm_memory_used_bytes", "jvm_memory_max_bytes", "Heap"),
                heapAvailableBytes(metricSelector, "jvm_memory_used_bytes", "jvm_memory_committed_bytes", "Heap")
        ));
        overview.setJvmHeapTotalBytes(firstPresentNumber(
                heapBytes(metricSelector, "jvm_memory_bytes_max", "heap"),
                heapBytes(metricSelector, "jvm_memory_bytes_committed", "heap"),
                heapBytes(metricSelector, "jvm_memory_max_bytes", "heap"),
                heapBytes(metricSelector, "jvm_memory_committed_bytes", "heap"),
                heapBytes(metricSelector, "jvm_memory_bytes_max", "Heap"),
                heapBytes(metricSelector, "jvm_memory_bytes_committed", "Heap"),
                heapBytes(metricSelector, "jvm_memory_max_bytes", "Heap"),
                heapBytes(metricSelector, "jvm_memory_committed_bytes", "Heap")
        ));
        overview.setBrokerCpuPercent(firstPresentNumber(
                cpuPercent("jvm_OperatingSystem_ProcessCpuLoad", metricSelector),
                cpuPercent("jvm_operatingsystem_processcpuload", metricSelector),
                "sum(rate(process_cpu_seconds_total{job=\"kafka_jmx\"," + metricSelector + "}[5m])) * 100"
        ));
        Double systemCpuPercent = firstPresentNumber(
                cpuPercent("jvm_OperatingSystem_CpuLoad", metricSelector),
                cpuPercent("jvm_operatingsystem_cpuload", metricSelector),
                cpuPercent("jvm_OperatingSystem_SystemCpuLoad", metricSelector),
                cpuPercent("jvm_operatingsystem_systemcpuload", metricSelector)
        );
        if (systemCpuPercent == null && isExternal(cluster)) {
            systemCpuPercent = computeExternalSystemCpuPercent(cluster, selectedNodeId);
        }
        overview.setSystemCpuPercent(systemCpuPercent);

        overview.setHostMemoryUsedPercent(computeHostMemoryPercent(cluster, selectedNodeId));
        overview.setHostMemoryAvailableMb(computeHostMemoryAvailableMb(cluster, selectedNodeId));
        overview.setHostMemoryTotalMb(computeHostMemoryTotalMb(cluster, selectedNodeId));

        if (overview.getKafkaExporterUp() == null) {
            overview.getWarnings().add("Prometheus has no kafka_exporter samples for this cluster yet.");
        }
        if (!Boolean.TRUE.equals(overview.getJmxAvailable())) {
            overview.getWarnings().add("JMX exporter target is not configured. Showing kafka_exporter-level monitoring only.");
        } else if (overview.getJmxUp() == null || overview.getJmxUp() <= 0) {
            overview.getWarnings().add("JMX exporter target is configured but Prometheus has no recent JMX samples for this cluster.");
        }
        return overview;
    }



    public boolean prometheusHealthy() {
        try {
            JsonNode response = prometheusGet("/api/v1/query", Map.of("query", "up"));
            return response != null && "success".equals(response.path("status").asText());
        } catch (Exception e) {
            log.warn("Prometheus health check failed: {}", e.getMessage());
            return false;
        }
    }

    private void addInternalTargets(List<SdTargetGroup> targets, Cluster cluster) {
        if (cluster.getServices() == null) {
            return;
        }
        for (ClusterServiceAssignment service : cluster.getServices()) {
            Host host = hostRepository.findById(service.getHostId()).orElse(null);
            String hostIp = hostIp(host);
            if (hostIp == null) {
                continue;
            }
            String role = service.getRole();
            String nodeId = service.getNodeId() == null ? null : String.valueOf(service.getNodeId());
            if (Boolean.TRUE.equals(cluster.getJmxEnabled()) && isBrokerRole(role)) {
                int port = validExporterPort(service.getJmxExporterPort()) ? service.getJmxExporterPort() : jmxPort(cluster);
                addJmxTarget(targets, cluster, hostIp, port, role, nodeId);
                if (port != DEFAULT_JMX_PORT) {
                    addJmxTarget(targets, cluster, hostIp, DEFAULT_JMX_PORT, role, nodeId);
                }
                
                // Add node-level kafka_exporter target
                targets.add(group(hostIp + ":" + kafkaExporterPortBase, labels(cluster, "kafka_exporter", role, nodeId)));
            }
            if (Boolean.TRUE.equals(cluster.getNodeExporterEnabled())) {
                int port = service.getNodeExporterPort() != null ? service.getNodeExporterPort() : nodeExporterPort(cluster);
                targets.add(group(hostIp + ":" + port, labels(cluster, "node", role, nodeId)));
            }
        }
    }

    private void addJmxTarget(List<SdTargetGroup> targets, Cluster cluster, String hostIp, int port, String role, String nodeId) {
        if (port <= 0) {
            return;
        }
        String target = hostIp + ":" + port;
        boolean exists = targets.stream()
                .filter(group -> group.getLabels() != null && "kafka_jmx".equals(group.getLabels().get("job")))
                .anyMatch(group -> group.getTargets() != null && group.getTargets().contains(target));
        if (!exists) {
            targets.add(group(target, labels(cluster, "kafka_jmx", role, nodeId)));
        }
    }

    private void addExternalJmxTargets(List<SdTargetGroup> targets, Cluster cluster) {
        if (!Boolean.TRUE.equals(cluster.getJmxEnabled())) {
            return;
        }
        for (ExternalClusterNode node : externalClusterNodeRepository.findByClusterId(cluster.getId())) {
            if (node.getJmxExporterPort() == null || node.getHost() == null || node.getHost().isBlank()) {
                continue;
            }
            String role = Boolean.TRUE.equals(node.getIsController()) && !Boolean.TRUE.equals(node.getIsBroker())
                    ? "controller"
                    : "broker";
            String nodeId = node.getNodeId() == null ? null : String.valueOf(node.getNodeId());
            targets.add(group(node.getHost() + ":" + node.getJmxExporterPort(), labels(cluster, "kafka_jmx", role, nodeId)));
            targets.add(group(node.getHost() + ":" + kafkaExporterPortBase, labels(cluster, "kafka_exporter", role, nodeId)));
        }
    }

    private void addExternalJmxTargets(List<SdTargetGroup> targets, ExternalCluster cluster) {
        for (ExternalClusterNode node : externalClusterNodeRepository.findByClusterId(cluster.getId())) {
            if (node.getHost() == null || node.getHost().isBlank()) {
                continue;
            }
            Integer port = node.getJmxExporterPort() != null ? node.getJmxExporterPort() : defaultJmxExporterPort;
            String role = Boolean.TRUE.equals(node.getIsController()) && !Boolean.TRUE.equals(node.getIsBroker())
                    ? "controller"
                    : "broker";
            String nodeId = node.getNodeId() == null ? null : String.valueOf(node.getNodeId());
            targets.add(group(node.getHost() + ":" + port, labels(cluster, "kafka_jmx", role, nodeId)));
            targets.add(group(node.getHost() + ":" + kafkaExporterPortBase, labels(cluster, "kafka_exporter", role, nodeId)));
        }
    }

    private Optional<String> exporterTarget(Cluster cluster) {
        return exporterHost(cluster).map(host -> host + ":" + exporterPort(cluster));
    }

    private Optional<String> exporterHost(Cluster cluster) {
        if (cluster.getKafkaExporterHost() != null && !cluster.getKafkaExporterHost().isBlank()) {
            return Optional.of(cluster.getKafkaExporterHost().trim());
        }
        if (defaultExporterHost != null && !defaultExporterHost.isBlank()) {
            return Optional.of(defaultExporterHost.trim());
        }
        return Optional.empty();
    }

    private int exporterPort(Cluster cluster) {
        if (cluster.getKafkaExporterPort() != null && cluster.getKafkaExporterPort() > 0) {
            return cluster.getKafkaExporterPort();
        }
        return kafkaExporterPortBase + Math.floorMod(cluster.getId().hashCode(), 500);
    }

    private int jmxPort(Cluster cluster) {
        if (validExporterPort(cluster.getJmxExporterPort())) {
            return cluster.getJmxExporterPort();
        }
        return defaultJmxExporterPort > 0 ? defaultJmxExporterPort : DEFAULT_JMX_PORT;
    }

    private boolean validExporterPort(Integer port) {
        return port != null && port >= 1024 && port <= 65535;
    }

    private int nodeExporterPort(Cluster cluster) {
        if (cluster.getNodeExporterPort() != null && cluster.getNodeExporterPort() > 0) {
            return cluster.getNodeExporterPort();
        }
        return DEFAULT_NODE_EXPORTER_PORT;
    }

    private boolean hasJmxTargets(Cluster cluster) {
        if (!Boolean.TRUE.equals(cluster.getJmxEnabled())) {
            return false;
        }
        if (isExternal(cluster)) {
            return externalClusterNodeRepository.findByClusterId(cluster.getId()).stream()
                    .anyMatch(node -> node.getHost() != null && !node.getHost().isBlank());
        }
        return cluster.getServices() != null && cluster.getServices().stream()
                .filter(service -> isBrokerRole(service.getRole()))
                .map(ClusterServiceAssignment::getHostId)
                .map(hostRepository::findById)
                .map(optional -> optional.map(this::hostIp).orElse(null))
                .anyMatch(ip -> ip != null && !ip.isBlank());
    }

    private boolean hasJmxTargets(ExternalCluster cluster) {
        return !externalClusterNodeRepository.findByClusterId(cluster.getId()).isEmpty();
    }

    private List<MonitoringNodeSummary> monitoringNodes(Cluster cluster) {
        Map<String, MonitoringNodeSummary> nodes = new LinkedHashMap<>();
        if (cluster.getServices() != null) {
            for (ClusterServiceAssignment service : cluster.getServices()) {
                String key = service.getNodeId() == null ? service.getHostId() : String.valueOf(service.getNodeId());
                if (key == null || key.isBlank()) {
                    continue;
                }
                MonitoringNodeSummary node = nodes.computeIfAbsent(key, ignored -> {
                    MonitoringNodeSummary created = new MonitoringNodeSummary();
                    created.setNodeId(service.getNodeId() == null ? null : String.valueOf(service.getNodeId()));
                    created.setHostId(service.getHostId());
                    Host host = service.getHostId() == null ? null : hostRepository.findById(service.getHostId()).orElse(null);
                    created.setHostname(host == null ? service.getHostId() : host.getHostname());
                    created.setHostIp(hostIp(host));
                    return created;
                });
                node.setRole(mergeRole(node.getRole(), roleLabel(service.getRole())));
            }
        }

        // External clusters are mirrored into kf_clusters, but their discovered
        // topology is stored in kf_external_cluster_nodes rather than service
        // assignments. Enrich the mirror so monitoring selectors can list and
        // target every discovered broker/controller.
        if (isExternal(cluster)) {
            for (MonitoringNodeSummary externalNode : externalMonitoringNodes(cluster.getId())) {
                String key = externalNode.getNodeId() == null ? externalNode.getHostIp() : externalNode.getNodeId();
                if (key == null || key.isBlank()) {
                    continue;
                }
                MonitoringNodeSummary node = nodes.get(key);
                if (node == null) {
                    nodes.put(key, externalNode);
                    continue;
                }
                if (node.getHostname() == null || node.getHostname().isBlank()) {
                    node.setHostname(externalNode.getHostname());
                }
                if (node.getHostIp() == null || node.getHostIp().isBlank()) {
                    node.setHostIp(externalNode.getHostIp());
                }
                node.setRole(mergeRole(node.getRole(), externalNode.getRole()));
            }
        }
        return new ArrayList<>(nodes.values());
    }

    private List<MonitoringNodeSummary> monitoringNodes(ExternalCluster cluster) {
        return externalMonitoringNodes(cluster.getId());
    }

    private List<MonitoringNodeSummary> externalMonitoringNodes(UUID clusterId) {
        Map<String, MonitoringNodeSummary> nodes = new LinkedHashMap<>();
        for (ExternalClusterNode externalNode : externalClusterNodeRepository.findByClusterId(clusterId)) {
            String key = externalNode.getNodeId() == null ? externalNode.getHost() : String.valueOf(externalNode.getNodeId());
            if (key == null || key.isBlank()) {
                continue;
            }
            MonitoringNodeSummary node = new MonitoringNodeSummary();
            node.setNodeId(externalNode.getNodeId() == null ? null : String.valueOf(externalNode.getNodeId()));
            node.setHostIp(externalNode.getHost());
            node.setHostname(externalNode.getHost());
            node.setRole(externalRoleLabel(externalNode));
            nodes.put(key, node);
        }
        return new ArrayList<>(nodes.values());
    }

    private String selectedNodeId(List<MonitoringNodeSummary> nodes, String requestedNodeId) {
        if (requestedNodeId == null || requestedNodeId.isBlank()) {
            return null;
        }
        String normalized = requestedNodeId.trim();
        return nodes.stream()
                .map(MonitoringNodeSummary::getNodeId)
                .filter(value -> value != null && value.equals(normalized))
                .findFirst()
                .orElse(null);
    }

    private boolean isBrokerRole(String role) {
        if (role == null) {
            return false;
        }
        String normalized = role.trim().toLowerCase(Locale.ROOT);
        return "broker".equals(normalized)
                || "broker_controller".equals(normalized)
                || "broker+controller".equals(normalized)
                || "broker_zookeeper".equals(normalized);
    }

    private String roleLabel(String role) {
        if (role == null || role.isBlank()) {
            return "Kafka";
        }
        String normalized = role.trim().toLowerCase(Locale.ROOT);
        if ("broker_controller".equals(normalized) || "broker+controller".equals(normalized)) {
            return "Broker + Controller";
        }
        if ("broker".equals(normalized)) {
            return "Broker";
        }
        if ("controller".equals(normalized)) {
            return "Controller";
        }
        return role.trim();
    }

    private String externalRoleLabel(ExternalClusterNode node) {
        boolean broker = Boolean.TRUE.equals(node.getIsBroker());
        boolean controller = Boolean.TRUE.equals(node.getIsController());
        if (broker && controller) {
            return "Broker + Controller";
        }
        if (broker) {
            return "Broker";
        }
        if (controller) {
            return "Controller";
        }
        return "Kafka";
    }

    private String mergeRole(String current, String next) {
        if (next == null || next.isBlank()) {
            return current;
        }
        if (current == null || current.isBlank()) {
            return next;
        }
        return current.contains(next) ? current : current + ", " + next;
    }

    private String monitoringWarning(Cluster cluster) {
        if (!Boolean.TRUE.equals(cluster.getMonitoringEnabled())) {
            return "Monitoring is disabled for this cluster.";
        }
        if (isExternal(cluster) && !hasJmxTargets(cluster)) {
            return "External JMX exporter target not found. Showing kafka_exporter-level monitoring only.";
        }
        return null;
    }

    private SdTargetGroup group(String target, Map<String, String> labels) {
        SdTargetGroup group = new SdTargetGroup();
        group.setTargets(List.of(target));
        group.setLabels(labels);
        return group;
    }

    private Map<String, String> labels(Cluster cluster, String job, String role, String nodeId) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("job", job);
        labels.put("cluster", cluster.getName());
        labels.put("cluster_id", cluster.getId().toString());
        labels.put("origin", origin(cluster).toLowerCase(Locale.ROOT));
        labels.put("env", cluster.getEnvironment() == null || cluster.getEnvironment().isBlank() ? "unknown" : cluster.getEnvironment());
        if (role != null && !role.isBlank()) {
            labels.put("role", role);
        }
        if (nodeId != null && !nodeId.isBlank()) {
            labels.put("node_id", nodeId);
        }
        return labels;
    }

    private Map<String, String> labels(ExternalCluster cluster, String job, String role, String nodeId) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("job", job);
        labels.put("cluster", cluster.getName());
        labels.put("cluster_id", cluster.getId().toString());
        labels.put("origin", "external");
        labels.put("env", cluster.getEnvironment() == null || cluster.getEnvironment().isBlank() ? "unknown" : cluster.getEnvironment());
        if (role != null && !role.isBlank()) {
            labels.put("role", role);
        }
        if (nodeId != null && !nodeId.isBlank()) {
            labels.put("node_id", nodeId);
        }
        return labels;
    }

    private String origin(Cluster cluster) {
        String originType = normalizeOrigin(cluster.getOriginType());
        if (originType != null) {
            return originType;
        }
        String legacyModeOrigin = normalizeOrigin(cluster.getMode());
        return legacyModeOrigin != null ? legacyModeOrigin : "INTERNAL";
    }

    private boolean isExternal(Cluster cluster) {
        return "EXTERNAL".equals(origin(cluster));
    }

    private String normalizeOrigin(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if ("INTERNAL".equals(normalized) || "EXTERNAL".equals(normalized)) {
            return normalized;
        }
        return null;
    }

    Double computeHostMemoryPercent(Cluster cluster, String nodeId) {
        MemoryUsage memory = hostMemory(cluster, nodeId);
        if (memory == null || memory.totalMb() <= 0) {
            return null;
        }
        return Math.min(100.0, Math.round((memory.usedMb() * 1000.0) / memory.totalMb()) / 10.0);
    }

    Long computeHostMemoryAvailableMb(Cluster cluster, String nodeId) {
        MemoryUsage memory = hostMemory(cluster, nodeId);
        return memory == null ? null : Math.max(0L, memory.totalMb() - memory.usedMb());
    }

    Long computeHostMemoryTotalMb(Cluster cluster, String nodeId) {
        MemoryUsage memory = hostMemory(cluster, nodeId);
        return memory == null ? null : memory.totalMb();
    }

    Double computeExternalSystemCpuPercent(Cluster cluster, String nodeId) {
        if (!isExternal(cluster)) {
            return null;
        }
        Map<String, Double> hostCpu = new LinkedHashMap<>();
        for (ExternalClusterNode node : externalClusterNodeRepository.findByClusterId(cluster.getId())) {
            if (nodeId != null && (node.getNodeId() == null || !nodeId.equals(String.valueOf(node.getNodeId())))) {
                continue;
            }
            if (node.getCpuUsagePct() == null) {
                continue;
            }
            String hostKey = node.getHost() == null || node.getHost().isBlank()
                    ? "node:" + node.getNodeId()
                    : node.getHost().trim().toLowerCase(Locale.ROOT);
            hostCpu.put(hostKey, Math.max(0.0, Math.min(100.0, node.getCpuUsagePct())));
        }
        if (hostCpu.isEmpty()) {
            return null;
        }
        return hostCpu.values().stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    private MemoryUsage hostMemory(Cluster cluster, String nodeId) {
        if (isExternal(cluster)) {
            return externalHostMemory(cluster, nodeId);
        }
        if (cluster.getServices() == null || cluster.getServices().isEmpty()) {
            return null;
        }
        long totalMb = 0;
        long usedMb = 0;
        int counted = 0;
        Map<String, Host> countedHosts = new LinkedHashMap<>();
        for (ClusterServiceAssignment service : cluster.getServices()) {
            if (nodeId != null && (service.getNodeId() == null || !nodeId.equals(String.valueOf(service.getNodeId())))) {
                continue;
            }
            if (service.getHostId() == null || countedHosts.containsKey(service.getHostId())) {
                continue;
            }
            Host host = hostRepository.findById(service.getHostId()).orElse(null);
            if (host == null || host.getMemTotalMb() == null || host.getMemTotalMb() <= 0) {
                continue;
            }
            countedHosts.put(service.getHostId(), host);
            totalMb += host.getMemTotalMb();
            usedMb += host.getMemUsedMb() == null ? 0 : host.getMemUsedMb();
            counted++;
        }
        if (counted == 0 || totalMb <= 0) {
            return null;
        }
        return new MemoryUsage(usedMb, totalMb);
    }

    private MemoryUsage externalHostMemory(Cluster cluster, String nodeId) {
        long totalMb = 0;
        long usedMb = 0;
        int counted = 0;
        for (ExternalClusterNode node : externalClusterNodeRepository.findByClusterId(cluster.getId())) {
            if (nodeId != null && (node.getNodeId() == null || !nodeId.equals(String.valueOf(node.getNodeId())))) {
                continue;
            }
            if (node.getMemoryTotalMb() == null || node.getMemoryTotalMb() <= 0) {
                continue;
            }
            totalMb += node.getMemoryTotalMb();
            usedMb += node.getMemoryUsedMb() == null ? 0 : node.getMemoryUsedMb();
            counted++;
        }
        if (counted == 0 || totalMb <= 0) {
            return null;
        }
        return new MemoryUsage(usedMb, totalMb);
    }

    private record MemoryUsage(long usedMb, long totalMb) {}

    private String hostIp(Host host) {
        if (host == null) {
            return null;
        }
        if (host.getHostIp() != null && !host.getHostIp().isBlank()) {
            return host.getHostIp().trim();
        }
        if (host.getIpAddresses() == null || host.getIpAddresses().isBlank()) {
            return null;
        }
        try {
            List<String> values = objectMapper.readValue(host.getIpAddresses(), new TypeReference<>() {});
            return values.stream().filter(value -> value != null && !value.isBlank()).findFirst().orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private String labelSelector(UUID clusterId) {
        return "cluster_id=\"" + clusterId + "\"";
    }

    private String labelSelector(UUID clusterId, String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            return labelSelector(clusterId);
        }
        return labelSelector(clusterId) + ",node_id=\"" + promLabelValue(nodeId) + "\"";
    }

    private String promLabelValue(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private Double firstNumber(String promql) {
        try {
            JsonNode response = prometheusGet("/api/v1/query", Map.of("query", promql));
            JsonNode result = response == null ? null : response.path("data").path("result");
            if (result == null || !result.isArray() || result.isEmpty()) {
                return null;
            }
            String value = result.get(0).path("value").get(1).asText();
            double parsed = Double.parseDouble(value);
            return Double.isFinite(parsed) ? parsed : null;
        } catch (Exception e) {
            log.debug("Prometheus query failed: {}", promql, e);
            return null;
        }
    }

    private Double firstOrZero(String promql) {
        Double value = firstNumber(promql);
        return value == null ? 0.0 : value;
    }

    private Double firstPresentNumber(String... promqls) {
        for (String promql : promqls) {
            Double value = firstNumber(promql);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String[] append(String[] values, String value) {
        String[] result = new String[values.length + 1];
        System.arraycopy(values, 0, result, 0, values.length);
        result[values.length] = value;
        return result;
    }

    private String[] brokerTopicRate(String selector, String metric) {
        String lower = metric.toLowerCase(Locale.ROOT);
        return new String[] {
                "sum(kafka_server_BrokerTopicMetrics_" + metric + "_OneMinuteRate{" + selector + "})",
                "sum(kafka_server_BrokerTopicMetrics_" + metric + "_FiveMinuteRate{" + selector + "})",
                "sum(kafka_server_BrokerTopicMetrics_" + metric + "_MeanRate{" + selector + "})",
                "sum(rate(kafka_server_BrokerTopicMetrics_" + metric + "_Count{" + selector + "}[5m]))",
                "sum(kafka_server_brokertopicmetrics_" + lower + "_oneminuterate{" + selector + "})",
                "sum(kafka_server_brokertopicmetrics_" + lower + "_fiveminuterate{" + selector + "})",
                "sum(kafka_server_brokertopicmetrics_" + lower + "_meanrate{" + selector + "})",
                "sum(rate(kafka_server_brokertopicmetrics_" + lower + "_count{" + selector + "}[5m]))",
                "sum(kafka_server_brokertopicmetrics_oneminuterate{" + selector + ",name=\"" + metric + "\"})",
                "sum(kafka_server_brokertopicmetrics_fiveminuterate{" + selector + ",name=\"" + metric + "\"})",
                "sum(kafka_server_brokertopicmetrics_meanrate{" + selector + ",name=\"" + metric + "\"})",
                "sum(rate(kafka_server_brokertopicmetrics_count{" + selector + ",name=\"" + metric + "\"}[5m]))",
                "sum(rate(kafka_server_brokertopicmetrics_" + prometheusCounterName(metric) + "_total{" + selector + "}[5m]))"
        };
    }

    private String prometheusCounterName(String metric) {
        if ("MessagesInPerSec".equals(metric)) {
            return "messagesin";
        }
        if ("BytesInPerSec".equals(metric)) {
            return "bytesin";
        }
        if ("BytesOutPerSec".equals(metric)) {
            return "bytesout";
        }
        return metric.toLowerCase(Locale.ROOT);
    }

    private String heapPercent(String selector, String usedMetric, String limitMetric, String area) {
        return "(sum(" + usedMetric + "{" + selector + ",area=\"" + area + "\"}) / sum(" + limitMetric + "{" + selector + ",area=\"" + area + "\"})) * 100";
    }

    private String heapAvailableBytes(String selector, String usedMetric, String limitMetric, String area) {
        return "clamp_min(sum(" + limitMetric + "{" + selector + ",area=\"" + area + "\"}) - sum("
                + usedMetric + "{" + selector + ",area=\"" + area + "\"}), 0)";
    }

    private String heapBytes(String selector, String metric, String area) {
        return "sum(" + metric + "{" + selector + ",area=\"" + area + "\"})";
    }

    private String cpuPercent(String metric, String selector) {
        return "clamp_min(clamp_max(max(" + metric + "{" + selector + "}) * 100, 100), 0)";
    }

    private JsonNode prometheusGet(String path, Map<String, String> params) {
        URI uri = prometheusUri(path, params);
        if (!isGrafanaProxyMode()) {
            return restTemplate.getForObject(uri, JsonNode.class);
        }

        HttpHeaders headers = new HttpHeaders();
        if (grafanaUsername != null && !grafanaUsername.isBlank()) {
            headers.setBasicAuth(grafanaUsername, grafanaPassword == null ? "" : grafanaPassword);
        }
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                uri,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                JsonNode.class
        );
        return response.getBody();
    }

    private URI prometheusUri(String path, Map<String, String> params) {
        String baseUrl;
        String effectivePath = path;
        if (isGrafanaProxyMode()) {
            if (grafanaUrl == null || grafanaUrl.isBlank()) {
                throw new IllegalStateException("Grafana URL is not configured");
            }
            if (grafanaDatasourceUid == null || grafanaDatasourceUid.isBlank()) {
                throw new IllegalStateException("Grafana datasource UID is not configured");
            }
            baseUrl = grafanaUrl;
            effectivePath = "/api/datasources/proxy/uid/" + grafanaDatasourceUid + path;
        } else {
            baseUrl = prometheusUrl;
        }

        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("Prometheus URL is not configured");
        }
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseUrl).path(effectivePath);
        params.forEach(builder::queryParam);
        return builder.build().encode().toUri();
    }

    private boolean isGrafanaProxyMode() {
        return "grafana-proxy".equalsIgnoreCase(monitoringMode);
    }

    private String monitoringSourceUrl() {
        if (isGrafanaProxyMode()) {
            return grafanaUrl + "/api/datasources/proxy/uid/" + grafanaDatasourceUid;
        }
        if (prometheusUrl == null || prometheusUrl.isBlank()) {
            return null;
        }
        return prometheusUrl;
    }

    private String kafkaExporterArgs(Cluster cluster, boolean maskSecrets) {
        String bootstrapServers = cluster.getBootstrapServers();
        if (bootstrapServers == null || bootstrapServers.isBlank()) {
            return "--kafka.server=<bootstrap-server:9092>";
        }
        List<String> args = new ArrayList<>();
        for (String server : bootstrapServers.split(",")) {
            String trimmed = server.trim();
            if (!trimmed.isBlank()) {
                args.add("--kafka.server=" + systemdArg(trimmed));
            }
        }

        if (isExternal(cluster)) {
            externalCluster(cluster).ifPresent(external -> addExternalExporterSecurityArgs(args, external, maskSecrets));
        }
        return String.join(" ", args);
    }

    private Optional<ExternalCluster> externalCluster(Cluster cluster) {
        if (cluster == null || !isExternal(cluster)) {
            return Optional.empty();
        }
        Optional<ExternalCluster> byId = externalClusterRepository.findById(cluster.getId());
        if (byId.isPresent()) {
            return byId;
        }
        if (cluster.getKafkaClusterId() != null && !cluster.getKafkaClusterId().isBlank()) {
            Optional<ExternalCluster> byKafkaId = externalClusterRepository.findByKafkaClusterId(cluster.getKafkaClusterId());
            if (byKafkaId.isPresent()) {
                return byKafkaId;
            }
        }
        if (cluster.getName() != null && !cluster.getName().isBlank()) {
            return externalClusterRepository.findByName(cluster.getName());
        }
        return Optional.empty();
    }

    private void addExternalExporterSecurityArgs(List<String> args, ExternalCluster external, boolean maskSecrets) {
        String protocol = firstNonBlank(external.getSecurityProtocol(), external.getSecurity());
        String normalizedProtocol = protocol == null ? "" : protocol.trim().toUpperCase(Locale.ROOT);
        if (normalizedProtocol.contains("SSL")) {
            args.add("--tls.enabled");
            String truststoreType = normalizeCertificateType(external.getTruststoreType());
            boolean hasPemCaFile = hasText(external.getTruststorePath()) && isPemCertificateType(truststoreType);
            if (Boolean.TRUE.equals(external.getDisableHostnameVerification()) || !hasPemCaFile) {
                args.add("--tls.insecure-skip-tls-verify");
            }
            if (hasPemCaFile) {
                args.add("--tls.ca-file=" + systemdArg(external.getTruststorePath().trim()));
            }

            String keystoreType = normalizeCertificateType(external.getKeystoreType());
            if (hasText(external.getKeystorePath()) && isPemCertificateType(keystoreType)) {
                args.add("--tls.cert-file=" + systemdArg(external.getKeystorePath().trim()));
            }
        }
        if (normalizedProtocol.contains("SASL")) {
            args.add("--sasl.enabled");
            if (hasText(external.getSaslUsername())) {
                args.add("--sasl.username=" + systemdArg(external.getSaslUsername().trim()));
            }
            String password = maskSecrets ? "********" : decryptOrBlank(external.getSaslPasswordEncrypted());
            if (hasText(password)) {
                args.add("--sasl.password=" + systemdArg(password));
            }
            String mechanism = kafkaExporterSaslMechanism(external.getSaslMechanism());
            if (mechanism != null) {
                args.add("--sasl.mechanism=" + systemdArg(mechanism));
            }
        }
    }

    private String normalizeCertificateType(String certificateType) {
        return certificateType == null ? "" : certificateType.trim().toUpperCase(Locale.ROOT);
    }

    private boolean isPemCertificateType(String certificateType) {
        return "PEM".equals(certificateType) || "CRT".equals(certificateType) || "CERT".equals(certificateType);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String decryptOrBlank(String encrypted) {
        if (encrypted == null || encrypted.isBlank()) {
            return null;
        }
        try {
            return encryptionService.decrypt(encrypted);
        } catch (Exception e) {
            log.warn("Could not decrypt external kafka_exporter credential", e);
            return null;
        }
    }

    private String kafkaExporterSaslMechanism(String mechanism) {
        if (mechanism == null || mechanism.isBlank()) {
            return null;
        }
        return switch (mechanism.trim().toUpperCase(Locale.ROOT)) {
            case "PLAIN" -> "plain";
            case "SCRAM-SHA-256", "SCRAM_SHA_256", "SCRAMSHA256" -> "scram-sha256";
            case "SCRAM-SHA-512", "SCRAM_SHA_512", "SCRAMSHA512" -> "scram-sha512";
            default -> mechanism.trim().toLowerCase(Locale.ROOT);
        };
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }

    private String systemdArg(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @Data
    public static class SdTargetGroup {
        private List<String> targets;
        private Map<String, String> labels;
    }

    @Data
    public static class MonitoringClusterSummary {
        private UUID id;
        private String name;
        private String originType;
        private Boolean monitoringEnabled;
        private String kafkaExporterTarget;
        private Boolean jmxAvailable;
        private String warning;
        private List<MonitoringNodeSummary> nodes;
    }

    @Data
    public static class MonitoringNodeSummary {
        private String nodeId;
        private String hostId;
        private String hostname;
        private String hostIp;
        private String role;
    }

    @Data
    public static class MonitoringOverview {
        private UUID clusterId;
        private String name;
        private String originType;
        private String prometheusUrl;
        private String kafkaExporterTarget;
        private Boolean jmxAvailable;
        private Double kafkaExporterUp;
        private Double jmxUp;
        private Double brokerCount;
        private Double topicCount;
        private Double partitionCount;
        private Double underReplicatedPartitions;
        private Double consumerLag;
        private Double messagesInPerSecond;
        private Double bytesInPerSecond;
        private Double bytesOutPerSecond;
        private Double jvmHeapUsedPercent;
        private Double jvmHeapAvailableBytes;
        private Double jvmHeapTotalBytes;
        private Double brokerCpuPercent;
        private Double systemCpuPercent;
        private Double hostMemoryUsedPercent;
        private Long hostMemoryAvailableMb;
        private Long hostMemoryTotalMb;
        private String selectedNodeId;
        private List<MonitoringNodeSummary> nodes;
        private List<String> warnings;
    }

    @Data
    public static class ExporterPlan {
        private UUID clusterId;
        private String serviceName;
        private Integer port;
        private String target;
        private String unit;
        private List<String> installCommands;
    }
}
