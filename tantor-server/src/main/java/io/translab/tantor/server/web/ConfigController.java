package io.translab.tantor.server.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.translab.tantor.server.domain.Cluster;
import io.translab.tantor.server.domain.ClusterServiceAssignment;
import io.translab.tantor.server.repository.ClusterRepository;
import io.translab.tantor.server.repository.HostRepository;
import io.translab.tantor.server.service.DeploymentService;
import io.translab.tantor.server.service.KafkaAdminService;
import io.translab.tantor.server.service.ActivityAlertService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/clusters/{clusterId}/config")
@RequiredArgsConstructor
public class ConfigController {

    private final ClusterRepository clusterRepository;
    private final HostRepository hostRepository;
    private final DeploymentService deploymentService;
    private final KafkaAdminService kafkaAdminService;
    private final ObjectMapper objectMapper;
    private final ActivityAlertService activityAlertService;
    private final io.translab.tantor.server.service.ExternalClusterService externalClusterService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> getBrokerConfigs(@PathVariable UUID clusterId) {
        Cluster cluster = clusterRepository.findById(clusterId).orElse(null);
        if (cluster == null) return ResponseEntity.notFound().build();

        Map<Integer, Map<String, Object>> dynamicConfigs = kafkaAdminService.getBrokerConfigs(clusterId);
        
        Map<String, Object> response = new java.util.HashMap<>();
        response.put("dynamicConfigs", dynamicConfigs);
        
        Map<String, Object> staticConfigs = new HashMap<>();
        Map<String, Object> deploymentConfig = new HashMap<>();
        String installDir = "/data/apps/kafka/install";
        
        try {
            if (cluster.getConfigJson() != null && !cluster.getConfigJson().isEmpty()) {
                deploymentConfig = objectMapper.readValue(cluster.getConfigJson(), Map.class);
                if (deploymentConfig.containsKey("kafka_install_dir")) {
                    installDir = String.valueOf(deploymentConfig.get("kafka_install_dir"));
                }
            }
        } catch(Exception e) {
            deploymentConfig = new HashMap<>();
        }

        Map<String, Object> activeProperties = buildActiveServerProperties(cluster, deploymentConfig, installDir);
        String activeFilePath = activeServerConfigPath(cluster, installDir);
        
        staticConfigs.put("filePath", activeFilePath);
        staticConfigs.put("properties", activeProperties);
        staticConfigs.put("deploymentParameters", deploymentConfig);
        staticConfigs.put("configFiles", buildConfigFiles(cluster, deploymentConfig, installDir, activeFilePath, activeProperties));
        response.put("staticConfigs", staticConfigs);

        return ResponseEntity.ok(response);
    }

    private List<Map<String, Object>> buildConfigFiles(
            Cluster cluster,
            Map<String, Object> config,
            String installDir,
            String activeFilePath,
            Map<String, Object> activeProperties
    ) {
        List<Map<String, Object>> files = new ArrayList<>();
        files.add(configFile(
                "active-server",
                "Active Server Config",
                "server.properties used by the Kafka service",
                activeFilePath,
                "server",
                true,
                activeProperties
        ));

        if ("zookeeper".equalsIgnoreCase(cluster.getMode())) {
            files.add(configFile(
                    "zookeeper",
                    "ZooKeeper Config",
                    "zookeeper.properties used by ZooKeeper service",
                    installDir + "/config/zookeeper.properties",
                    "zookeeper",
                    false,
                    buildZooKeeperProperties(config, installDir)
            ));
        }
        return files;
    }

    private Map<String, Object> configFile(
            String id,
            String label,
            String description,
            String path,
            String role,
            boolean active,
            Map<String, Object> properties
    ) {
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("id", id);
        file.put("label", label);
        file.put("description", description);
        file.put("path", path);
        file.put("role", role);
        file.put("active", active);
        file.put("properties", properties);
        return file;
    }

    private String activeServerConfigPath(Cluster cluster, String installDir) {
        return "zookeeper".equalsIgnoreCase(cluster.getMode())
                ? installDir + "/config/server.properties"
                : installDir + "/config/kraft/server.properties";
    }

    private Map<String, Object> buildActiveServerProperties(Cluster cluster, Map<String, Object> config, String installDir) {
        return "zookeeper".equalsIgnoreCase(cluster.getMode())
                ? buildZooKeeperBackedBrokerProperties(cluster, config, installDir)
                : buildKraftServerProperties(cluster, config, installDir);
    }

    private Map<String, Object> buildKraftServerProperties(Cluster cluster, Map<String, Object> config, String installDir) {
        Map<String, Object> props = new LinkedHashMap<>();
        String host = firstBrokerHost(cluster, config);
        String nodeId = firstNodeId(cluster, config);
        String listenerPort = stringConfig(config, "listener_port", firstBootstrapPort(cluster, "9092"));
        String controllerPort = stringConfig(config, "controller_port", "9093");
        String dataDir = stringConfig(config, "kafka_data_dir", installDir + "/data");
        String logDirs = stringConfig(config, "log_dirs", dataDir + "/kafka-logs");
        String role = processRoles(stringConfig(config, "role", firstServiceRole(cluster)));
        String quorumVoters = stringConfig(config, "quorum_voters", nodeId + "@" + host + ":" + controllerPort);
        String listeners = stringConfig(config, "listeners", "PLAINTEXT://" + host + ":" + listenerPort + ",CONTROLLER://" + host + ":" + controllerPort);
        String advertisedListeners = stringConfig(config, "advertised_listeners",
                stringConfig(config, "advertised.listeners", "PLAINTEXT://" + host + ":" + listenerPort));

        props.put("process.roles", role);
        props.put("node.id", nodeId);
        props.put("controller.quorum.voters", quorumVoters);
        props.put("listeners", listeners);
        props.put("inter.broker.listener.name", "PLAINTEXT");
        props.put("advertised.listeners", advertisedListeners);
        props.put("controller.listener.names", "CONTROLLER");
        props.put("listener.security.protocol.map", "CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT,SSL:SSL,SASL_PLAINTEXT:SASL_PLAINTEXT,SASL_SSL:SASL_SSL");
        props.put("log.dirs", logDirs);
        props.put("num.partitions", stringConfig(config, "num_partitions", "1"));
        String replicationFactor = stringConfig(config, "replication_factor", "1");
        props.put("offsets.topic.replication.factor", replicationFactor);
        props.put("transaction.state.log.replication.factor", replicationFactor);
        props.put("transaction.state.log.min.isr", "1");
        return props;
    }

    private Map<String, Object> buildZooKeeperBackedBrokerProperties(Cluster cluster, Map<String, Object> config, String installDir) {
        Map<String, Object> props = new LinkedHashMap<>();
        String host = firstBrokerHost(cluster, config);
        String nodeId = firstNodeId(cluster, config);
        String listenerPort = stringConfig(config, "listener_port", firstBootstrapPort(cluster, "9092"));
        String dataDir = stringConfig(config, "kafka_data_dir", installDir + "/data");
        String logDirs = stringConfig(config, "log_dirs", dataDir + "/kafka-logs");
        String zookeeperConnect = stringConfig(config, "zookeeper_connect", host + ":" + stringConfig(config, "zookeeper_port", "2181"));

        props.put("broker.id", nodeId);
        props.put("listeners", "PLAINTEXT://" + host + ":" + listenerPort);
        props.put("advertised.listeners", "PLAINTEXT://" + host + ":" + listenerPort);
        props.put("zookeeper.connect", zookeeperConnect);
        props.put("zookeeper.connection.timeout.ms", "18000");
        props.put("log.dirs", logDirs);
        props.put("num.partitions", stringConfig(config, "num_partitions", "1"));
        String replicationFactor = stringConfig(config, "replication_factor", "1");
        props.put("offsets.topic.replication.factor", replicationFactor);
        props.put("transaction.state.log.replication.factor", replicationFactor);
        props.put("transaction.state.log.min.isr", "1");
        return props;
    }

    private Map<String, Object> buildZooKeeperProperties(Map<String, Object> config, String installDir) {
        Map<String, Object> props = new LinkedHashMap<>();
        String dataDir = stringConfig(config, "zookeeper_data_dir", installDir + "/zookeeper-data");
        props.put("tickTime", "2000");
        props.put("initLimit", "10");
        props.put("syncLimit", "5");
        props.put("dataDir", dataDir);
        props.put("clientPort", stringConfig(config, "zookeeper_port", "2181"));
        props.put("maxClientCnxns", "0");
        props.put("admin.enableServer", "false");
        Object servers = config.get("zookeeper_servers");
        if (servers != null && !String.valueOf(servers).isBlank()) {
            props.put("servers", servers);
        }
        return props;
    }

    private String stringConfig(Map<String, Object> config, String key, String defaultValue) {
        Object value = config.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            return defaultValue;
        }
        return String.valueOf(value);
    }

    private String firstNodeId(Cluster cluster, Map<String, Object> config) {
        Object configured = config.get("node_id");
        if (configured != null && !String.valueOf(configured).isBlank()) {
            return String.valueOf(configured);
        }
        if (cluster.getServices() != null && !cluster.getServices().isEmpty() && cluster.getServices().get(0).getNodeId() != null) {
            return String.valueOf(cluster.getServices().get(0).getNodeId());
        }
        return "1";
    }

    private String firstServiceRole(Cluster cluster) {
        if (cluster.getServices() != null && !cluster.getServices().isEmpty() && cluster.getServices().get(0).getRole() != null) {
            return cluster.getServices().get(0).getRole();
        }
        return "broker_controller";
    }

    private String processRoles(String role) {
        if (role == null || role.isBlank() || "broker_controller".equalsIgnoreCase(role)) {
            return "broker,controller";
        }
        if ("broker_zookeeper".equalsIgnoreCase(role)) {
            return "broker";
        }
        return role.replace('_', ',');
    }

    private String firstBrokerHost(Cluster cluster, Map<String, Object> config) {
        String listeners = stringConfig(config, "advertised_listeners", stringConfig(config, "advertised.listeners", ""));
        if (!listeners.isBlank()) {
            String host = hostFromEndpoint(listeners.split(",")[0]);
            if (!host.isBlank()) return host;
        }
        if (cluster.getBootstrapServers() != null && !cluster.getBootstrapServers().isBlank()) {
            String host = hostFromEndpoint(cluster.getBootstrapServers().split(",")[0]);
            if (!host.isBlank()) return host;
        }
        String assignedHost = assignedHostAddress(cluster);
        if (!assignedHost.isBlank()) return assignedHost;
        return "localhost";
    }

    private String assignedHostAddress(Cluster cluster) {
        if (cluster.getServices() == null || cluster.getServices().isEmpty()) {
            return "";
        }
        for (ClusterServiceAssignment service : cluster.getServices()) {
            String hostId = service.getHostId();
            if (hostId == null || hostId.isBlank()) {
                continue;
            }
            String ip = hostRepository.findById(hostId)
                    .map(host -> firstAddressFromJson(host.getIpAddresses()))
                    .orElse("");
            if (!ip.isBlank()) {
                return ip;
            }
        }
        return "";
    }

    private String firstAddressFromJson(String ipAddressesJson) {
        if (ipAddressesJson == null || ipAddressesJson.isBlank()) {
            return "";
        }
        try {
            List<?> addresses = objectMapper.readValue(ipAddressesJson, List.class);
            for (Object address : addresses) {
                String value = String.valueOf(address);
                if (!value.isBlank() && !value.startsWith("127.") && !"localhost".equalsIgnoreCase(value)) {
                    return value;
                }
            }
        } catch (Exception ignored) {
            String cleaned = ipAddressesJson.replace("[", "").replace("]", "").replace("\"", "");
            for (String part : cleaned.split(",")) {
                String value = part.trim();
                if (!value.isBlank() && !value.startsWith("127.") && !"localhost".equalsIgnoreCase(value)) {
                    return value;
                }
            }
        }
        return "";
    }

    private String firstBootstrapPort(Cluster cluster, String fallback) {
        if (cluster.getBootstrapServers() != null && !cluster.getBootstrapServers().isBlank()) {
            String endpoint = cluster.getBootstrapServers().split(",")[0];
            int idx = endpoint.lastIndexOf(':');
            if (idx > -1 && idx < endpoint.length() - 1) {
                return endpoint.substring(idx + 1).replaceAll("[^0-9]", "");
            }
        }
        return fallback;
    }

    private String hostFromEndpoint(String endpoint) {
        if (endpoint == null) return "";
        String value = endpoint.trim();
        int scheme = value.indexOf("://");
        if (scheme >= 0) {
            value = value.substring(scheme + 3);
        }
        int slash = value.indexOf('/');
        if (slash >= 0) {
            value = value.substring(0, slash);
        }
        int colon = value.lastIndexOf(':');
        if (colon > 0) {
            value = value.substring(0, colon);
        }
        return value.trim();
    }

    @PutMapping("/bulk")
    public ResponseEntity<?> updateConfigBulk(@PathVariable UUID clusterId, @RequestBody BulkConfigRequest request) throws JsonProcessingException {
        Cluster cluster = clusterRepository.findById(clusterId).orElse(null);
        if (cluster == null) return ResponseEntity.notFound().build();

        // 1. Update live dynamically via AdminClient
        Map<Integer, Map<String, Object>> currentConfigs = kafkaAdminService.getBrokerConfigs(clusterId);
        boolean dynamicSuccess = true;
        String dynamicError = null;
        for (Integer brokerId : currentConfigs.keySet()) {
            try {
                kafkaAdminService.alterBrokerConfig(clusterId, brokerId, request.getConfigKey(), request.getConfigValue());
            } catch (Exception e) {
                dynamicSuccess = false;
                dynamicError = e.getMessage();
                // If not applying to agents, we must fail immediately
                if (!request.isApplyToAgents()) {
                    return ResponseEntity.badRequest().body(Map.of("message", "Failed to alter broker config dynamically: " + e.getMessage()));
                }
            }
        }

        // 2. Optionally push to static file and restart via Agent
        if (request.isApplyToAgents()) {
            if ("EXTERNAL".equalsIgnoreCase(cluster.getMode())) {
                externalClusterService.queueConfigUpdate(clusterId, request.getConfigKey(), request.getConfigValue(), request.isRestart());
                activityAlertService.logActivity(
                    "INFO",
                    "Queued external agent config persistence: " + request.getConfigKey() + " = " + request.getConfigValue() + (request.isRestart() ? " (restart requested)" : ""),
                    clusterId
                );
                return ResponseEntity.ok().build();
            }

            // Need to update the DB blob so the agents get the new properties
            Map<String, Object> dbConfig = new java.util.HashMap<>();
            if (cluster.getConfigJson() != null && !cluster.getConfigJson().isEmpty()) {
                Map<String, Object> existing = objectMapper.readValue(cluster.getConfigJson(), Map.class);
                if (existing != null) {
                    dbConfig.putAll(existing);
                }
            }
            dbConfig.put(request.getConfigKey(), request.getConfigValue());
            String newConfigStr = objectMapper.writeValueAsString(dbConfig);
            cluster.setConfigJson(newConfigStr);
            clusterRepository.save(cluster);

            for (ClusterServiceAssignment svc : cluster.getServices()) {
                if ("broker".equals(svc.getRole()) || "broker_controller".equals(svc.getRole()) || "broker_zookeeper".equals(svc.getRole())) {
                    deploymentService.updateKafkaConfig(clusterId, svc.getHostId(), newConfigStr, request.isRestart());
                }
            }
            activityAlertService.logActivity(
                "INFO",
                "Updated server.properties config: " + request.getConfigKey() + " = " + request.getConfigValue() + (request.isRestart() ? " (Restarting brokers)" : ""),
                clusterId
            );
        } else if (dynamicSuccess) {
            activityAlertService.logActivity(
                "INFO",
                "Dynamically updated broker config: " + request.getConfigKey() + " = " + request.getConfigValue(),
                clusterId
            );
        }

        return ResponseEntity.ok().build();
    }

    @Data
    public static class BulkConfigRequest {
        private String configKey;
        private String configValue;
        private boolean applyToAgents = false;
        private boolean restart = false;
    }
}
