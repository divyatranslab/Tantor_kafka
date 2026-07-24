package io.translab.tantor.server.service;

import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;
import io.translab.tantor.server.domain.*;
import io.translab.tantor.server.repository.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import java.util.*;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ConfigQueryService {
    private final ClusterRepository clusterRepository;
    private final HostRepository hostRepository;
    private final KafkaAdminService kafkaAdminService;
    private final ObjectMapper objectMapper;
    private final ExternalClusterService externalClusterService;
    private final DiscoveryAgentRepository discoveryAgentRepository;
    private final ExternalClusterRepository externalClusterRepository;
    private final ExternalClusterNodeRepository externalClusterNodeRepository;

        @GetMapping
        public ResponseEntity<Map<String, Object>> getBrokerConfigs(@PathVariable UUID clusterId) {
            Cluster cluster = clusterRepository.findById(clusterId).orElse(null);
            if (cluster != null && !"EXTERNAL".equalsIgnoreCase(cluster.getMode())) {
                Map<Integer, Map<String, Object>> dynamicConfigs = kafkaAdminService.getBrokerConfigs(clusterId);
                
                Map<String, Object> response = new java.util.HashMap<>();
                response.put("dynamicConfigs", dynamicConfigs);
                
                Map<String, Object> staticConfigs = new HashMap<>();
                Map<String, Object> deploymentConfig = new HashMap<>();
                
                try {
                    if (cluster.getConfigJson() != null && !cluster.getConfigJson().isEmpty()) {
                        deploymentConfig = objectMapper.readValue(cluster.getConfigJson(), Map.class);
                    }
                } catch(Exception e) {
                    deploymentConfig = new HashMap<>();
                }
    
                String installDir = ConfigUtil.activeKafkaInstallDir(deploymentConfig);
                Map<String, Object> activeProperties = buildActiveServerProperties(cluster, deploymentConfig, installDir);
                String activeFilePath = ConfigUtil.activeServerConfigPath(cluster, installDir);
                
                staticConfigs.put("filePath", activeFilePath);
                staticConfigs.put("properties", activeProperties);
                staticConfigs.put("deploymentParameters", deploymentConfig);
                staticConfigs.put("configFiles", buildConfigFiles(cluster, deploymentConfig, installDir, activeFilePath, activeProperties));
                response.put("serviceTopology", buildServiceTopology(cluster, deploymentConfig, installDir));
                response.put("staticConfigs", staticConfigs);
    
                return ResponseEntity.ok(response);
            }
    
            io.translab.tantor.server.domain.ExternalCluster externalCluster = externalClusterRepository.findById(clusterId).orElse(null);
            if (externalCluster == null) return ResponseEntity.notFound().build();
    
            Map<String, Object> response = new HashMap<>();
            response.put("dynamicConfigs", new HashMap<>());
            
            List<Map<String, Object>> topology = new ArrayList<>();
            List<Map<String, Object>> configFiles = new ArrayList<>();
            
            List<io.translab.tantor.server.domain.ExternalClusterNode> nodes = externalClusterNodeRepository.findByClusterId(clusterId);
            List<io.translab.tantor.server.domain.DiscoveryAgent> allAgents = discoveryAgentRepository.findAll();
            
            for (io.translab.tantor.server.domain.ExternalClusterNode node : nodes) {
                Map<String, Object> topoNode = new HashMap<>();
                topoNode.put("hostId", node.getHost());
                topoNode.put("hostAddress", node.getHost());
                
                String role = "Broker";
                if (Boolean.TRUE.equals(node.getIsBroker()) && Boolean.TRUE.equals(node.getIsController())) role = "Broker + Controller";
                else if (Boolean.TRUE.equals(node.getIsController())) role = "Controller";
                
                topoNode.put("role", role);
                topoNode.put("nodeId", node.getNodeId() != null ? node.getNodeId() : Math.abs(node.getHost().hashCode()));
                topoNode.put("isBroker", node.getIsBroker());
                topoNode.put("isController", node.getIsController());
                topoNode.put("serviceName", "kafka.service"); // fallback, actual restart uses agent's discovered service
                String configPath = node.getConfigFile();
                topoNode.put("configPath", configPath);
                topoNode.put("configFilePath", configPath);
                topoNode.put("configFileName", configPath != null ? new java.io.File(configPath).getName() : "server.properties");
                
                boolean canExecute = false;
                for (io.translab.tantor.server.domain.DiscoveryAgent agent : allAgents) {
                    if (matchesDiscoveryAgent(agent, node.getHost()) && "ONLINE".equalsIgnoreCase(agent.getStatus())) {
                        canExecute = Boolean.TRUE.equals(agent.getCanExecuteTasks());
                        break;
                    }
                }
                topoNode.put("canExecuteTasks", canExecute);
                
                topology.add(topoNode);
                
                Map<String, Object> staticFile = new HashMap<>();
                staticFile.put("id", "ext_" + topoNode.get("nodeId"));
                staticFile.put("nodeId", topoNode.get("nodeId"));
                staticFile.put("serviceId", node.getId().toString());
                staticFile.put("hostId", node.getHost());
                staticFile.put("label", role + " Properties (" + node.getHost() + ")");
                staticFile.put("path", configPath);
                staticFile.put("role", role);
                staticFile.put("properties", new HashMap<>());
                configFiles.add(staticFile);
            }
            
            response.put("serviceTopology", topology);
            Map<String, Object> staticConfigs = new HashMap<>();
            staticConfigs.put("configFiles", configFiles);
            response.put("staticConfigs", staticConfigs);
            
            return ResponseEntity.ok(response);
    
    
        }

        @PostMapping("/read")
        public ResponseEntity<Map<String, Object>> readConfig(@PathVariable UUID clusterId, @RequestBody Map<String, Object> request) {
            String nodeIdStr = String.valueOf(request.get("nodeId"));
            Integer targetNodeId = null;
            try { targetNodeId = Integer.parseInt(nodeIdStr); } catch (Exception e) {}
    
            List<io.translab.tantor.server.domain.ExternalClusterNode> nodes = externalClusterNodeRepository.findByClusterId(clusterId);
            io.translab.tantor.server.domain.ExternalClusterNode targetNode = null;
            for (io.translab.tantor.server.domain.ExternalClusterNode n : nodes) {
                if (n.getNodeId() != null && n.getNodeId().equals(targetNodeId)) {
                    targetNode = n;
                    break;
                } else if (n.getNodeId() == null && Math.abs(n.getHost().hashCode()) == targetNodeId) {
                    targetNode = n;
                    break;
                }
            }
    
            if (targetNode == null) {
                return ResponseEntity.badRequest().body(Map.of("message", "Node not found"));
            }
    
            Map<String, Object> payload = new HashMap<>();
            payload.put("configFilePath", targetNode.getConfigFile());
    
            Map<String, Object> taskResponse = externalClusterService.queueTask(clusterId, targetNode.getHost(), "read_config", payload);
            return ResponseEntity.ok(taskResponse);
        }

    public List<Map<String, Object>> buildConfigFiles(
                Cluster cluster,
                Map<String, Object> config,
                String installDir,
                String activeFilePath,
                Map<String, Object> activeProperties
        ) {
            List<Map<String, Object>> files = new ArrayList<>();
    
            if (!"EXTERNAL".equalsIgnoreCase(cluster.getMode()) && cluster.getServices() != null && !cluster.getServices().isEmpty()) {
                for (ClusterServiceAssignment service : cluster.getServices()) {
                    if ("zookeeper".equalsIgnoreCase(service.getRole())) continue;
                    Map<String, Object> serviceConfig = ConfigUtil.serviceConfig(config, service, objectMapper);
                    String serviceInstallDir = ConfigUtil.activeKafkaInstallDir(serviceConfig);
                    Map<String, Object> properties = ConfigUtil.storedProperties(service, objectMapper);
                    if (properties.isEmpty()) {
                        properties = "zookeeper".equalsIgnoreCase(cluster.getMode())
                                ? buildZooKeeperServiceProperties(cluster, serviceConfig, serviceInstallDir, service)
                                : buildKraftServiceProperties(cluster, serviceConfig, serviceInstallDir, service);
                    }
                    files.add(configFile(
                            ConfigUtil.serviceConfigId(service),
                            ConfigUtil.serviceConfigLabel(service),
                            ConfigUtil.serviceConfigDescription(service),
                        ConfigUtil.serviceConfigPath(service.getRole(), cluster.getMode(), cluster.getKafkaVersion(), serviceInstallDir),
                            service.getRole(),
                            true,
                            properties,
                            service
                    ));
                }
                return files;
            }
    
            files.add(configFile(
                    "active-server",
                    "Active Server Config",
                    "server.properties used by the Kafka service",
                    activeFilePath,
                    "server",
                    true,
                    activeProperties,
                    null
            ));
    
            return files;
        }

    public Map<String, Object> configFile(
                String id,
                String label,
                String description,
                String path,
                String role,
                boolean active,
                Map<String, Object> properties,
                ClusterServiceAssignment service
        ) {
            Map<String, Object> file = new LinkedHashMap<>();
            file.put("id", id);
            file.put("label", label);
            file.put("description", description);
            file.put("path", path);
            file.put("role", role);
            file.put("active", active);
            file.put("properties", properties);
            if (service != null) {
                file.put("serviceId", service.getId());
                file.put("hostId", service.getHostId());
                file.put("nodeId", service.getNodeId());
            }
            return file;
        }

    public List<Map<String, Object>> buildServiceTopology(Cluster cluster, Map<String, Object> config, String installDir) {
            List<Map<String, Object>> topology = new ArrayList<>();
            if (cluster.getServices() == null) {
                return topology;
            }
            for (ClusterServiceAssignment service : cluster.getServices()) {
                Map<String, Object> serviceConfig = ConfigUtil.serviceConfig(config, service, objectMapper);
                String serviceInstallDir = ConfigUtil.activeKafkaInstallDir(serviceConfig);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("hostId", service.getHostId());
                item.put("hostAddress", hostAddressForService(service));
                item.put("role", service.getRole());
                item.put("nodeId", service.getNodeId());
                item.put("isBroker", ConfigUtil.isBrokerRole(service.getRole()));
                item.put("isController", ConfigUtil.isControllerRole(service.getRole()));
                item.put("serviceName", ConfigUtil.serviceNameForRole(service.getRole()) + ".service");
                item.put("systemdUnit", ConfigUtil.serviceNameForRole(service.getRole()) + ".service");
                item.put("configPath", ConfigUtil.serviceConfigPath(service.getRole(), cluster.getMode(), cluster.getKafkaVersion(), serviceInstallDir));
                item.put("listenerPort", ConfigUtil.isBrokerRole(service.getRole()) ? ConfigUtil.stringConfig(serviceConfig, "listener_port", "9092") : "");
                item.put("controllerPort", ConfigUtil.isControllerRole(service.getRole()) ? ConfigUtil.stringConfig(serviceConfig, "controller_port", "9093") : "");
                item.put("logDirs", ConfigUtil.isBrokerRole(service.getRole()) ? ConfigUtil.brokerLogDirs(serviceConfig, ConfigUtil.defaultKafkaDataDir(serviceConfig)) : "");
                item.put("metadataLogDir", ConfigUtil.metadataLogDirForRole(service.getRole(), serviceConfig, ConfigUtil.defaultKafkaDataDir(serviceConfig)));
                
                if ("EXTERNAL".equalsIgnoreCase(cluster.getMode())) {
                    discoveryAgentRepository.findById(service.getHostId()).ifPresentOrElse(agent -> {
                        item.put("agentStatus", agent.getStatus());
                        item.put("managedStatus", "managed");
                        item.put("canExecuteTasks", agent.getCanExecuteTasks() != null && agent.getCanExecuteTasks());
                    }, () -> {
                        item.put("agentStatus", "OFFLINE");
                        item.put("managedStatus", "unmanaged");
                        item.put("canExecuteTasks", false);
                    });
                } else {
                    item.put("agentStatus", "ONLINE");
                    item.put("managedStatus", "managed");
                    item.put("canExecuteTasks", true);
                }
                
                topology.add(item);
            }
            return topology;
        }

    public Map<String, Object> buildKraftServiceProperties(Cluster cluster, Map<String, Object> config, String installDir, ClusterServiceAssignment service) {
            Map<String, Object> props = new LinkedHashMap<>();
            String role = service.getRole();
            String host = hostAddressForService(service);
            String nodeId = service.getNodeId() == null ? "1" : String.valueOf(service.getNodeId());
            String listenerPort = ConfigUtil.stringConfig(config, "listener_port", "9092");
            String controllerPort = ConfigUtil.stringConfig(config, "controller_port", "9093");
            String dataDir = ConfigUtil.defaultKafkaDataDir(config);
            String quorumVoters = ConfigUtil.stringConfig(config, "quorum_voters", nodeId + "@" + host + ":" + controllerPort);
    
            props.put("process.roles", ConfigUtil.processRoles(role));
            props.put("node.id", nodeId);
            props.put("controller.quorum.voters", quorumVoters);
            if (ConfigUtil.isBrokerRole(role) && ConfigUtil.isControllerRole(role)) {
                props.put("listeners", "PLAINTEXT://" + host + ":" + listenerPort + ",CONTROLLER://" + host + ":" + controllerPort);
            } else if (ConfigUtil.isControllerRole(role)) {
                props.put("listeners", "CONTROLLER://" + host + ":" + controllerPort);
            } else {
                props.put("listeners", "PLAINTEXT://" + host + ":" + listenerPort);
            }
            if (ConfigUtil.isBrokerRole(role)) {
                props.put("advertised.listeners", "PLAINTEXT://" + host + ":" + listenerPort);
                props.put("inter.broker.listener.name", "PLAINTEXT");
                props.put("log.dirs", ConfigUtil.brokerLogDirs(config, dataDir));
            }
            props.put("controller.listener.names", "CONTROLLER");
            props.put("listener.security.protocol.map", "CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT,SSL:SSL,SASL_PLAINTEXT:SASL_PLAINTEXT,SASL_SSL:SASL_SSL");
            props.put("metadata.log.dir", ConfigUtil.metadataLogDirForRole(role, config, dataDir));
            if (ConfigUtil.isBrokerRole(role)) {
                props.put("num.partitions", ConfigUtil.stringConfig(config, "num_partitions", "1"));
                String replicationFactor = ConfigUtil.stringConfig(config, "replication_factor", "1");
                String minIsr = ConfigUtil.stringConfig(config, "min_insync_replicas", "1");
                props.put("default.replication.factor", replicationFactor);
                props.put("min.insync.replicas", minIsr);
                props.put("offsets.topic.replication.factor", replicationFactor);
                props.put("transaction.state.log.replication.factor", replicationFactor);
                props.put("transaction.state.log.min.isr", minIsr);
            }
            return props;
        }

    public Map<String, Object> buildZooKeeperServiceProperties(Cluster cluster, Map<String, Object> config, String installDir, ClusterServiceAssignment service) {
            if ("zookeeper".equals(service.getRole())) {
                return buildZooKeeperProperties(config, installDir);
            }
            Map<String, Object> props = new LinkedHashMap<>();
            String host = hostAddressForService(service);
            String nodeId = service.getNodeId() == null ? "1" : String.valueOf(service.getNodeId());
            String listenerPort = ConfigUtil.stringConfig(config, "listener_port", "9092");
            String dataDir = ConfigUtil.stringConfig(config, "kafka_data_dir", ConfigUtil.defaultKafkaDataDir(config));
            props.put("broker.id", nodeId);
            props.put("listeners", "PLAINTEXT://" + host + ":" + listenerPort);
            props.put("advertised.listeners", "PLAINTEXT://" + host + ":" + listenerPort);
            props.put("zookeeper.connect", ConfigUtil.stringConfig(config, "zookeeper_connect", "localhost:2181"));
            props.put("zookeeper.connection.timeout.ms", "18000");
            props.put("log.dirs", ConfigUtil.brokerLogDirs(config, dataDir));
            props.put("num.partitions", ConfigUtil.stringConfig(config, "num_partitions", "1"));
            String replicationFactor = ConfigUtil.stringConfig(config, "replication_factor", "1");
            props.put("default.replication.factor", replicationFactor);
            props.put("min.insync.replicas", ConfigUtil.stringConfig(config, "min_insync_replicas", "1"));
            props.put("offsets.topic.replication.factor", replicationFactor);
            props.put("transaction.state.log.replication.factor", replicationFactor);
            props.put("transaction.state.log.min.isr", ConfigUtil.stringConfig(config, "min_insync_replicas", "1"));
            return props;
        }

    public String hostAddressForService(ClusterServiceAssignment service) {
            if (service == null || service.getHostId() == null || service.getHostId().isBlank()) {
                return "localhost";
            }
            String ip = hostRepository.findById(service.getHostId())
                    .map(host -> ConfigUtil.firstAddressFromJson(host.getIpAddresses(), objectMapper))
                    .orElse("");
            return ip.isBlank() ? service.getHostId() : ip;
        }

    public Map<String, Object> buildActiveServerProperties(Cluster cluster, Map<String, Object> config, String installDir) {
            return "zookeeper".equalsIgnoreCase(cluster.getMode())
                    ? buildZooKeeperBackedBrokerProperties(cluster, config, installDir)
                    : buildKraftServerProperties(cluster, config, installDir);
        }

    public Map<String, Object> buildKraftServerProperties(Cluster cluster, Map<String, Object> config, String installDir) {
            Map<String, Object> props = new LinkedHashMap<>();
            String host = firstBrokerHost(cluster, config);
            String nodeId = ConfigUtil.firstNodeId(cluster, config);
            String listenerPort = ConfigUtil.stringConfig(config, "listener_port", ConfigUtil.firstBootstrapPort(cluster, "9092"));
            String controllerPort = ConfigUtil.stringConfig(config, "controller_port", "9093");
            String dataDir = ConfigUtil.stringConfig(config, "kafka_data_dir", ConfigUtil.defaultKafkaDataDir(config));
            String logDirs = ConfigUtil.stringConfig(config, "log_dirs", dataDir + "/kafka-logs");
            String role = ConfigUtil.processRoles(ConfigUtil.stringConfig(config, "role", ConfigUtil.firstServiceRole(cluster)));
            String quorumVoters = ConfigUtil.stringConfig(config, "quorum_voters", nodeId + "@" + host + ":" + controllerPort);
            String listeners = ConfigUtil.stringConfig(config, "listeners", "PLAINTEXT://" + host + ":" + listenerPort + ",CONTROLLER://" + host + ":" + controllerPort);
            String advertisedListeners = ConfigUtil.stringConfig(config, "advertised_listeners",
                    ConfigUtil.stringConfig(config, "advertised.listeners", "PLAINTEXT://" + host + ":" + listenerPort));
    
            props.put("process.roles", role);
            props.put("node.id", nodeId);
            props.put("controller.quorum.voters", quorumVoters);
            props.put("listeners", listeners);
            props.put("inter.broker.listener.name", "PLAINTEXT");
            props.put("advertised.listeners", advertisedListeners);
            props.put("controller.listener.names", "CONTROLLER");
            props.put("listener.security.protocol.map", "CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT,SSL:SSL,SASL_PLAINTEXT:SASL_PLAINTEXT,SASL_SSL:SASL_SSL");
            props.put("log.dirs", logDirs);
            props.put("num.partitions", ConfigUtil.stringConfig(config, "num_partitions", "1"));
            String replicationFactor = ConfigUtil.stringConfig(config, "replication_factor", "1");
            props.put("offsets.topic.replication.factor", replicationFactor);
            props.put("transaction.state.log.replication.factor", replicationFactor);
            props.put("transaction.state.log.min.isr", "1");
            return props;
        }

    public Map<String, Object> buildZooKeeperBackedBrokerProperties(Cluster cluster, Map<String, Object> config, String installDir) {
            Map<String, Object> props = new LinkedHashMap<>();
            String host = firstBrokerHost(cluster, config);
            String nodeId = ConfigUtil.firstNodeId(cluster, config);
            String listenerPort = ConfigUtil.stringConfig(config, "listener_port", ConfigUtil.firstBootstrapPort(cluster, "9092"));
            String dataDir = ConfigUtil.stringConfig(config, "kafka_data_dir", ConfigUtil.defaultKafkaDataDir(config));
            String logDirs = ConfigUtil.stringConfig(config, "log_dirs", dataDir + "/kafka-logs");
            String zookeeperConnect = ConfigUtil.stringConfig(config, "zookeeper_connect", host + ":" + ConfigUtil.stringConfig(config, "zookeeper_port", "2181"));
    
            props.put("broker.id", nodeId);
            props.put("listeners", "PLAINTEXT://" + host + ":" + listenerPort);
            props.put("advertised.listeners", "PLAINTEXT://" + host + ":" + listenerPort);
            props.put("zookeeper.connect", zookeeperConnect);
            props.put("zookeeper.connection.timeout.ms", "18000");
            props.put("log.dirs", logDirs);
            props.put("num.partitions", ConfigUtil.stringConfig(config, "num_partitions", "1"));
            String replicationFactor = ConfigUtil.stringConfig(config, "replication_factor", "1");
            props.put("offsets.topic.replication.factor", replicationFactor);
            props.put("transaction.state.log.replication.factor", replicationFactor);
            props.put("transaction.state.log.min.isr", "1");
            return props;
        }

    public Map<String, Object> buildZooKeeperProperties(Map<String, Object> config, String installDir) {
            Map<String, Object> props = new LinkedHashMap<>();
            String dataDir = ConfigUtil.stringConfig(config, "zookeeper_data_dir", ConfigUtil.defaultKafkaDataDir(config) + "/zookeeper-data");
            props.put("tickTime", "2000");
            props.put("initLimit", "10");
            props.put("syncLimit", "5");
            props.put("dataDir", dataDir);
            props.put("clientPort", ConfigUtil.stringConfig(config, "zookeeper_port", "2181"));
            props.put("maxClientCnxns", "0");
            props.put("admin.enableServer", "false");
            Object servers = config.get("zookeeper_servers");
            if (servers != null && !String.valueOf(servers).isBlank()) {
                props.put("servers", servers);
            }
            return props;
        }

    public String firstBrokerHost(Cluster cluster, Map<String, Object> config) {
            String listeners = ConfigUtil.stringConfig(config, "advertised_listeners", ConfigUtil.stringConfig(config, "advertised.listeners", ""));
            if (!listeners.isBlank()) {
                String host = ConfigUtil.hostFromEndpoint(listeners.split(",")[0]);
                if (!host.isBlank()) return host;
            }
            if (cluster.getBootstrapServers() != null && !cluster.getBootstrapServers().isBlank()) {
                String host = ConfigUtil.hostFromEndpoint(cluster.getBootstrapServers().split(",")[0]);
                if (!host.isBlank()) return host;
            }
            String assignedHost = assignedHostAddress(cluster);
            if (!assignedHost.isBlank()) return assignedHost;
            return "localhost";
        }

    public String assignedHostAddress(Cluster cluster) {
            if (cluster.getServices() == null || cluster.getServices().isEmpty()) {
                return "";
            }
            for (ClusterServiceAssignment service : cluster.getServices()) {
                String hostId = service.getHostId();
                if (hostId == null || hostId.isBlank()) {
                    continue;
                }
                String ip = hostRepository.findById(hostId)
                        .map(host -> ConfigUtil.firstAddressFromJson(host.getIpAddresses(), objectMapper))
                        .orElse("");
                if (!ip.isBlank()) {
                    return ip;
                }
            }
            return "";
        }

    public boolean matchesDiscoveryAgent(io.translab.tantor.server.domain.DiscoveryAgent agent, String host) {
            if (agent == null || host == null || host.isBlank()) {
                return false;
            }
            if (agent.getHostname() != null && agent.getHostname().equalsIgnoreCase(host)) {
                return true;
            }
            return ConfigUtil.parseAgentAddresses(agent.getIpAddresses(), objectMapper).stream()
                    .anyMatch(address -> address.equalsIgnoreCase(host));
        }

}
