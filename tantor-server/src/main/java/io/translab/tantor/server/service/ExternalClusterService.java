package io.translab.tantor.server.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.translab.tantor.server.domain.Cluster;
import io.translab.tantor.server.domain.ClusterServiceAssignment;
import io.translab.tantor.server.domain.Host;
import io.translab.tantor.server.repository.ClusterRepository;
import io.translab.tantor.server.repository.ClusterServiceAssignmentRepository;
import io.translab.tantor.server.repository.DiscoveryAgentRepository;
import io.translab.tantor.server.repository.HostRepository;
import io.translab.tantor.server.domain.ExternalCluster;
import io.translab.tantor.server.domain.DiscoveryAgent;
import io.translab.tantor.server.security.EncryptionService;
import io.translab.tantor.server.security.TruststoreStorageService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;
import io.translab.tantor.server.audit.AuditService;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import io.translab.tantor.server.repository.ExternalClusterRepository;

@org.springframework.stereotype.Service
@lombok.RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class ExternalClusterService {
    private final ExternalClusterTaskService externalClusterTaskService;
    private final ExternalClusterDiscoveryService externalClusterDiscoveryService;
    private final ExternalClusterConnectionService externalClusterConnectionService;
    private final ExternalClusterRegistrationService externalClusterRegistrationService;
    private final ExternalClusterHealthService externalClusterHealthService;

    public java.util.Map<String, String> getExternalTaskData(String taskId) { return externalClusterTaskService.getExternalTaskData(taskId); }
    public void removeExternalTask(String taskId) { externalClusterTaskService.removeExternalTask(taskId); }
    public java.util.Map<String, Object> testBootstrap(BootstrapExternalClusterRequest request) { return externalClusterConnectionService.testBootstrap(request); }
    @org.springframework.transaction.annotation.Transactional
    public io.translab.tantor.server.domain.ExternalCluster registerBootstrapCluster(BootstrapExternalClusterRequest request) { return externalClusterRegistrationService.registerBootstrapCluster(request); }
    @org.springframework.transaction.annotation.Transactional
    public java.util.Map<String, Object> recordDiscoveryReport(ExternalDiscoveryReport report) { return externalClusterRegistrationService.recordDiscoveryReport(report); }
    public java.util.List<java.util.Map<String, Object>> listPendingDiscoveries() { return externalClusterDiscoveryService.listPendingDiscoveries(); }
    @org.springframework.transaction.annotation.Transactional
    public java.util.Map<String, Object> recordDiscoveryAgentHeartbeat(ExternalDiscoveryReport report) { return externalClusterDiscoveryService.recordDiscoveryAgentHeartbeat(report); }
    public java.util.List<java.util.Map<String, Object>> listDiscoveryAgents() { return externalClusterDiscoveryService.listDiscoveryAgents(); }
    public java.util.Map<String, Object> inspectDiscovery(String discoveryKey) { return externalClusterConnectionService.inspectDiscovery(discoveryKey); }
    @org.springframework.transaction.annotation.Transactional
    public io.translab.tantor.server.domain.ExternalCluster connectDiscovery(String discoveryKey) { return externalClusterRegistrationService.connectDiscovery(discoveryKey); }
    @org.springframework.transaction.annotation.Transactional
    public java.util.Optional<io.translab.tantor.server.domain.ExternalCluster> deleteExternalCluster(java.util.UUID id) { return externalClusterRegistrationService.deleteExternalCluster(id); }
    @org.springframework.transaction.annotation.Transactional
    public io.translab.tantor.server.domain.ExternalCluster upsertDiscoveryCluster(ExternalDiscoveryReport report) { return externalClusterRegistrationService.upsertDiscoveryCluster(report); }
    @org.springframework.transaction.annotation.Transactional
    public void receiveMetrics(String clusterName, ExternalBrokerMetricsDto metrics) { externalClusterDiscoveryService.receiveMetrics(clusterName, metrics); }
    public java.util.List<java.util.Map<String, Object>> listExternalClusters() { return externalClusterRegistrationService.listExternalClusters(); }
    public java.util.Map<String, Object> queueRestart(java.util.UUID clusterId) { return externalClusterTaskService.queueRestart(clusterId); }
    public java.util.Map<String, Object> queueConfigUpdate(java.util.UUID clusterId, String configKey, String configValue, boolean restart) { return externalClusterTaskService.queueConfigUpdate(clusterId, configKey, configValue, restart); }
    public java.util.Map<String, Object> queueTask(java.util.UUID clusterId, String hostname, String taskName, java.util.Map<String, Object> payload) { return externalClusterTaskService.queueTask(clusterId, hostname, taskName, payload); }
    public java.util.Map<String, Object> pollAgentTask(String clusterName, String hostname, String bootstrap) { return externalClusterTaskService.pollAgentTask(clusterName, hostname, bootstrap); }
    public void completeAgentTask(String clusterName, String hostname, String bootstrap, AgentTaskCompletion completion) { externalClusterTaskService.completeAgentTask(clusterName, hostname, bootstrap, completion); }
    public java.util.Map<String, Object> getExternalTaskStatus(String taskId) { return externalClusterTaskService.getExternalTaskStatus(taskId); }
    public boolean isAgentManaged(io.translab.tantor.server.domain.ExternalCluster cluster) { return externalClusterDiscoveryService.isAgentManaged(cluster); }
    public java.util.List<ExternalBrokerRecord> brokerRecords(io.translab.tantor.server.domain.ExternalCluster cluster) { return externalClusterDiscoveryService.readBrokerRecords(cluster); }
    public void checkExternalClustersHealth() { externalClusterHealthService.checkExternalClustersHealth(); }

    @Data
    public static class BootstrapExternalClusterRequest {
        private String name;
        private String environment;
        private String bootstrapServers;
        private String kafkaVersion;
        private String kafkaMode;
        private String security; // Legacy or UI fallback
        private String securityProtocol;
        private String saslMechanism;
        private String saslUsername;
        private String saslPassword;
        private String truststoreType;
        private String truststorePassword;
        private String truststoreBase64;
        private String truststoreFilename;
        private String keystoreType;
        private String keystorePassword;
        private String keyPassword;
        private String keystoreBase64;
        private String keystoreFilename;
        private Boolean disableHostnameVerification;
        private String clusterId;
        private Integer brokerCount;
        private Boolean agentFound;
        private String discoveryKey;
        private String controllerId;
        private java.util.List<java.util.Map<String, Object>> brokers;
        private java.util.Map<String, String> selectedAgents;
    }

    @Data
    public static class ExternalDiscoveryReport {
        private String hostId;
        private String agentName;
        private String name;
        private String environment;
        private String bootstrapServers;
        private String kafkaVersion;
        private String kafkaClusterId;
        private String kafkaMode;
        private String security;
        private int brokerCount;
        private Integer nodeId;
        @JsonProperty("isRunning")
        private boolean isRunning;
        private String installPath;
        private String logDirs;
        private String configFile;
        private String dataDirs;
        private String hostname;
        private String ipAddresses;
        private String lastSeen;
        private boolean canExecuteTasks;
        private String listeners;
        private String advertisedListeners;
        private String processRoles;
        private Double cpuUsagePct;
        private Long memoryUsedMb;
        private Long memoryTotalMb;
        private Long diskUsedGb;
        private Long diskTotalGb;
    }

    @Data
    public static class ExternalBrokerMetricsDto {
        private String hostname;
        private String bootstrap;
        private Double cpuUsagePct;
        private Long memoryUsedMb;
        private Long memoryTotalMb;
        private Long diskUsedGb;
        private Long diskTotalGb;
        private Double messagesInPerSec;
        private Double bytesInPerSec;
    }

    @Data
    public static class ExternalBrokerRecord {
        private String hostname;
        private String bootstrap;
        private String kafkaMode;
        private String security;
        private String installPath;
        private String logDirs;
        private String role;
        private Integer nodeId;
        private boolean running;
        private String lastSeen;
        private Double cpuUsagePct;
        private Long memoryUsedMb;
        private Long memoryTotalMb;
        private Long diskUsedGb;
        private Long diskTotalGb;
        private Double messagesInPerSec;
        private Double bytesInPerSec;
        private String listeners;
        private String advertisedListeners;
        private String processRoles;
    }

    @Data
    public static class ExternalAgentTask {
        private String taskId;
        private String task;
        private String status;
        private String clusterName;
        private String hostname;
        private String bootstrap;
        private String configKey;
        private String configValue;
        private boolean restart;
        private String message;
        private String configFilePath;
        private String backupDirPath;
        private String backupFilePath;
        private Map<String, String> configChanges;
        private String serviceName;
        private Map<String, String> data;
    }

    @Data
    public static class AgentTaskCompletion {
        private String status;
        private String message;
        private Map<String, String> data;
    }
}
