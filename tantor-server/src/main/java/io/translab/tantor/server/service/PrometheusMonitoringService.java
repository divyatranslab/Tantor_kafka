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

    @EventListener(ApplicationReadyEvent.class)
    public void ensureKafkaExportersOnStartup() {
        reconcileKafkaExporters();
    }

    @Scheduled(fixedDelayString = "${tantor.monitoring.exporter-reconcile-delay-ms:60000}")
    public void reconcileKafkaExporters() {
        try {
            for (Cluster cluster : clusterRepository.findByStatusNot("DELETED")) {
                String status = cluster.getStatus() == null ? "" : cluster.getStatus().trim().toUpperCase(Locale.ROOT);
                if ("SUCCESS".equals(status) || "RUNNING".equals(status) || "AVAILABLE".equals(status)) {
                    ensureKafkaExporter(cluster);
                }
            }
        } catch (Exception e) {
            log.warn("Could not reconcile kafka_exporter services", e);
        }
    }

    @Transactional(readOnly = true)
    public List<SdTargetGroup> prometheusTargets() {
        List<SdTargetGroup> targets = new ArrayList<>();
        for (Cluster cluster : clusterRepository.findByStatusNot("DELETED")) {
            if (!Boolean.TRUE.equals(cluster.getMonitoringEnabled())) {
                continue;
            }
            exporterTarget(cluster).ifPresent(target ->
                    targets.add(group(target, labels(cluster, "kafka_exporter", null, null))));

            if (isExternal(cluster)) {
                addExternalJmxTargets(targets, cluster);
            } else {
                addInternalTargets(targets, cluster);
            }
        }
        return targets;
    }

    @Transactional(readOnly = true)
    public List<MonitoringClusterSummary> clusters(String type) {
        String normalizedType = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
        List<MonitoringClusterSummary> result = new ArrayList<>();
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
            result.add(summary);
        }
        return result;
    }

    @Transactional(readOnly = true)
    public MonitoringOverview overview(UUID clusterId) {
        Cluster cluster = clusterRepository.findWithServicesById(clusterId)
                .orElseThrow(() -> new IllegalArgumentException("Cluster not found"));

        MonitoringOverview overview = new MonitoringOverview();
        overview.setClusterId(cluster.getId());
        overview.setName(cluster.getName());
        overview.setOriginType(origin(cluster));
        overview.setPrometheusUrl(monitoringSourceUrl());
        overview.setKafkaExporterTarget(exporterTarget(cluster).orElse(null));
        overview.setJmxAvailable(hasJmxTargets(cluster));
        overview.setWarnings(new ArrayList<>());
        String warning = monitoringWarning(cluster);
        if (warning != null) {
            overview.getWarnings().add(warning);
        }

        String selector = labelSelector(cluster.getId());
        overview.setKafkaExporterUp(firstNumber("max(max_over_time(up{job=\"kafka_exporter\"," + selector + "}[90s]))"));
        overview.setJmxUp(firstNumber("max(max_over_time(up{job=\"kafka_jmx\"," + selector + "}[90s]))"));
        overview.setBrokerCount(firstNumber("max(kafka_brokers{" + selector + "})"));
        overview.setTopicCount(firstNumber("count(count by (topic) (kafka_topic_partitions{" + selector + "}))"));
        overview.setPartitionCount(firstNumber("sum(kafka_topic_partitions{" + selector + "})"));
        overview.setUnderReplicatedPartitions(firstOrZero("sum(kafka_topic_partition_under_replicated_partition{" + selector + "})"));
        overview.setConsumerLag(firstOrZero("sum(kafka_consumergroup_lag{" + selector + "})"));
        overview.setMessagesInPerSecond(firstPresentNumber(
                append(brokerTopicRate(selector, "MessagesInPerSec"),
                        "sum(rate(kafka_topic_partition_current_offset{" + selector + "}[5m]))")
        ));
        overview.setBytesInPerSecond(firstPresentNumber(brokerTopicRate(selector, "BytesInPerSec")));
        overview.setBytesOutPerSecond(firstPresentNumber(brokerTopicRate(selector, "BytesOutPerSec")));
        overview.setJvmHeapUsedPercent(firstPresentNumber(
                heapPercent(selector, "jvm_memory_bytes_used", "jvm_memory_bytes_max", "heap"),
                heapPercent(selector, "jvm_memory_bytes_used", "jvm_memory_bytes_committed", "heap"),
                heapPercent(selector, "jvm_memory_used_bytes", "jvm_memory_max_bytes", "heap"),
                heapPercent(selector, "jvm_memory_used_bytes", "jvm_memory_committed_bytes", "heap"),
                heapPercent(selector, "jvm_memory_bytes_used", "jvm_memory_bytes_max", "Heap"),
                heapPercent(selector, "jvm_memory_bytes_used", "jvm_memory_bytes_committed", "Heap"),
                heapPercent(selector, "jvm_memory_used_bytes", "jvm_memory_max_bytes", "Heap"),
                heapPercent(selector, "jvm_memory_used_bytes", "jvm_memory_committed_bytes", "Heap")
        ));
        overview.setBrokerCpuPercent(firstPresentNumber(
                cpuPercent("jvm_OperatingSystem_ProcessCpuLoad", selector),
                cpuPercent("jvm_operatingsystem_processcpuload", selector),
                "sum(rate(process_cpu_seconds_total{job=\"kafka_jmx\"," + selector + "}[5m])) * 100"
        ));
        overview.setSystemCpuPercent(firstPresentNumber(
                cpuPercent("jvm_OperatingSystem_SystemCpuLoad", selector),
                cpuPercent("jvm_OperatingSystem_CpuLoad", selector),
                cpuPercent("jvm_operatingsystem_systemcpuload", selector),
                cpuPercent("jvm_operatingsystem_cpuload", selector)
        ));

        overview.setHostMemoryUsedPercent(computeHostMemoryPercent(cluster));

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

    @Transactional(readOnly = true)
    public ExporterPlan exporterPlan(UUID clusterId) {
        Cluster cluster = clusterRepository.findById(clusterId)
                .orElseThrow(() -> new IllegalArgumentException("Cluster not found"));
        return buildExporterPlan(cluster, true);
    }

    @Transactional(readOnly = true)
    public void ensureKafkaExporter(UUID clusterId) {
        clusterRepository.findById(clusterId).ifPresent(this::ensureKafkaExporter);
    }

    public void ensureKafkaExporter(Cluster cluster) {
        if (cluster == null || !Boolean.TRUE.equals(cluster.getMonitoringEnabled())) {
            return;
        }
        if (cluster.getBootstrapServers() == null || cluster.getBootstrapServers().isBlank()) {
            log.warn("Skipping kafka_exporter setup for cluster {} because bootstrap servers are empty", cluster.getId());
            return;
        }
        Path exporterBinary = Path.of("/usr/local/bin/kafka_exporter");
        if (!Files.isExecutable(exporterBinary)) {
            log.warn("Skipping kafka_exporter setup for cluster {} because {} is missing or not executable",
                    cluster.getId(), exporterBinary);
            return;
        }

        ExporterPlan plan = buildExporterPlan(cluster, false);
        Path unitPath = Path.of("/etc/systemd/system/" + plan.getServiceName() + ".service");
        try {
            Files.createDirectories(unitPath.getParent());
            boolean unitChanged = !Files.exists(unitPath) || !plan.getUnit().equals(Files.readString(unitPath, StandardCharsets.UTF_8));
            if (unitChanged) {
                Files.writeString(unitPath, plan.getUnit(), StandardCharsets.UTF_8);
                runSystemCommand("systemctl", "daemon-reload");
                runSystemCommand("systemctl", "enable", plan.getServiceName());
                runSystemCommand("systemctl", "restart", plan.getServiceName());
                log.info("kafka_exporter service {} was installed/restarted for cluster {}", plan.getServiceName(), cluster.getId());
                return;
            }
            runSystemCommand("systemctl", "enable", plan.getServiceName());
            if (!systemCommandSucceeds("systemctl", "is-active", "--quiet", plan.getServiceName())) {
                runSystemCommand("systemctl", "restart", plan.getServiceName());
                log.info("kafka_exporter service {} was recovered for cluster {}", plan.getServiceName(), cluster.getId());
            }
        } catch (Exception e) {
            log.warn("Could not auto-start kafka_exporter for cluster {}. Use /api/v1/monitoring/clusters/{}/exporter-plan if manual setup is needed.",
                    cluster.getId(), cluster.getId(), e);
        }
    }

    private ExporterPlan buildExporterPlan(Cluster cluster, boolean maskSecrets) {
        int port = exporterPort(cluster);
        String serviceName = "tantor-kafka-exporter-" + cluster.getId();
        String exporterArgs = kafkaExporterArgs(cluster, maskSecrets);

        ExporterPlan plan = new ExporterPlan();
        plan.setClusterId(cluster.getId());
        plan.setServiceName(serviceName);
        plan.setPort(port);
        plan.setTarget(exporterHost(cluster).orElse("<set TANTOR_MONITORING_EXPORTER_HOST>") + ":" + port);
        plan.setUnit("""
                [Unit]
                Description=Tantor Kafka Exporter for %s
                After=network-online.target

                [Service]
                Type=simple
                ExecStart=/usr/local/bin/kafka_exporter --web.listen-address=:%d %s
                Restart=always
                RestartSec=5

                [Install]
                WantedBy=multi-user.target
                """.formatted(cluster.getName(), port, exporterArgs));
        plan.setInstallCommands(List.of(
                "sudo install -m 0755 kafka_exporter /usr/local/bin/kafka_exporter",
                "sudo tee /etc/systemd/system/" + serviceName + ".service >/dev/null <<'EOF'\n" + plan.getUnit() + "EOF",
                "sudo systemctl daemon-reload",
                "sudo systemctl enable --now " + serviceName
        ));
        return plan;
    }

    private void runSystemCommand(String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException(String.join(" ", command) + " timed out");
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException(String.join(" ", command) + " failed: " + output);
        }
    }

    private boolean systemCommandSucceeds(String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
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

    private String monitoringWarning(Cluster cluster) {
        if (!Boolean.TRUE.equals(cluster.getMonitoringEnabled())) {
            return "Monitoring is disabled for this cluster.";
        }
        if (exporterTarget(cluster).isEmpty()) {
            return "Kafka exporter host is not configured. Set TANTOR_MONITORING_EXPORTER_HOST or kf_clusters.kafka_exporter_host.";
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

    private Double computeHostMemoryPercent(Cluster cluster) {
        if (cluster.getServices() == null || cluster.getServices().isEmpty()) {
            return null;
        }
        long totalMb = 0;
        long usedMb = 0;
        int counted = 0;
        for (ClusterServiceAssignment service : cluster.getServices()) {
            Host host = hostRepository.findById(service.getHostId()).orElse(null);
            if (host == null || host.getMemTotalMb() == null || host.getMemTotalMb() <= 0) {
                continue;
            }
            totalMb += host.getMemTotalMb();
            usedMb += host.getMemUsedMb() == null ? 0 : host.getMemUsedMb();
            counted++;
        }
        if (counted == 0 || totalMb <= 0) {
            return null;
        }
        return Math.min(100.0, Math.round((usedMb * 1000.0) / totalMb) / 10.0);
    }

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
        private Double brokerCpuPercent;
        private Double systemCpuPercent;
        private Double hostMemoryUsedPercent;
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
