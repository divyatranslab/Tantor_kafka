package io.translab.tantor.server.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.translab.tantor.server.domain.Cluster;
import io.translab.tantor.server.domain.ClusterServiceAssignment;
import io.translab.tantor.server.domain.Host;
import io.translab.tantor.server.repository.ClusterRepository;
import io.translab.tantor.server.repository.HostRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class KafkaAdminService {

    private final ClusterRepository clusterRepository;
    private final HostRepository hostRepository;
    private final ObjectMapper objectMapper;

    private final Map<UUID, AdminClient> adminClients = new ConcurrentHashMap<>();

    public AdminClient getAdminClient(UUID clusterId) {
        return adminClients.computeIfAbsent(clusterId, this::createAdminClient);
    }

    private AdminClient createAdminClient(UUID clusterId) {
        Cluster cluster = clusterRepository.findById(clusterId)
                .orElseThrow(() -> new IllegalArgumentException("Cluster not found"));

        Properties props = new Properties();

        if ("EXTERNAL".equals(cluster.getMode()) && cluster.getBootstrapServers() != null) {
            String servers = cluster.getBootstrapServers();
            props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, servers);
            log.info("Using external bootstrap servers for cluster {}: {}", clusterId, servers);
        } else {
            List<String> bootstrapServers = new ArrayList<>();
            for (ClusterServiceAssignment svc : cluster.getServices()) {
                if ("broker".equals(svc.getRole()) || "broker_controller".equals(svc.getRole())) {
                    Host host = hostRepository.findById(svc.getHostId()).orElse(null);
                    if (host != null) {
                        try {
                            String configJson = cluster.getConfigJson();
                            int port = 9092;
                            if (configJson != null && !configJson.isEmpty()) {
                                Map<String, Object> config = objectMapper.readValue(configJson, Map.class);
                                if (config.containsKey("listeners")) {
                                    String listeners = (String) config.get("listeners");
                                    String[] parts = listeners.split(":");
                                    port = Integer.parseInt(parts[parts.length - 1]);
                                } else if (config.containsKey("listener_port")) {
                                    Object portObj = config.get("listener_port");
                                    if (portObj instanceof Number) {
                                        port = ((Number) portObj).intValue();
                                    } else if (portObj instanceof String) {
                                        port = Integer.parseInt((String) portObj);
                                    }
                                }
                            }
                            List<String> ips = objectMapper.readValue(host.getIpAddresses(), new TypeReference<List<String>>() {});
                            if (!ips.isEmpty()) {
                                bootstrapServers.add(ips.get(0) + ":" + port);
                            }
                        } catch (Exception e) {
                            log.error("Error generating bootstrap servers for cluster {}: {}", clusterId, e.getMessage(), e);
                            try {
                                List<String> ips = objectMapper.readValue(host.getIpAddresses(), new TypeReference<List<String>>() {});
                                if (!ips.isEmpty()) bootstrapServers.add(ips.get(0) + ":9092");
                            } catch (Exception ex) {
                                log.warn("Failed to parse IPs for host {}", host.getId());
                            }
                        }
                    }
                }
            }

            if (bootstrapServers.isEmpty()) {
                throw new RuntimeException("No brokers found for cluster " + clusterId);
            }

            props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, String.join(",", bootstrapServers));
        }
        props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "10000");
        props.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "10000");

        log.info("Creating AdminClient for cluster {} with bootstrap {}", clusterId, props.get(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG));
        return AdminClient.create(props);
    }

    public void refreshAdminClient(UUID clusterId) {
        AdminClient oldClient = adminClients.remove(clusterId);
        if (oldClient != null) {
            oldClient.close();
        }
    }

    @PreDestroy
    public void closeAll() {
        adminClients.values().forEach(AdminClient::close);
        adminClients.clear();
    }

    public List<Map<String, Object>> listTopics(UUID clusterId) {
        AdminClient client = getAdminClient(clusterId);
        try {
            ListTopicsOptions options = new ListTopicsOptions().listInternal(false);
            Set<String> topicNames = client.listTopics(options).names().get();
            DescribeTopicsResult describeTopicsResult = client.describeTopics(topicNames);
            Map<String, TopicDescription> descriptions = describeTopicsResult.allTopicNames().get();

            return descriptions.values().stream().map(desc -> {
                Map<String, Object> map = new HashMap<>();
                map.put("name", desc.name());
                map.put("underReplicated", desc.partitions().stream().filter(p -> p.replicas().size() > p.isr().size()).count());
                return map;
            }).collect(Collectors.toList());
        } catch (InterruptedException | ExecutionException e) {
            log.error("Failed to list topics", e);
            refreshAdminClient(clusterId);
            throw new RuntimeException("Failed to list topics: " + e.getMessage());
        }
    }

    public io.translab.tantor.server.dto.PaginatedResponse<io.translab.tantor.server.dto.TopicSummaryDto> listTopicsPaginated(UUID clusterId, int page, int size, String search, String sortBy) {
        AdminClient client = getAdminClient(clusterId);
        try {
            ListTopicsOptions options = new ListTopicsOptions().listInternal(false);
            Set<String> allTopicNames = client.listTopics(options).names().get();

            // Filter and Sort in memory
            List<String> filteredNames = allTopicNames.stream()
                    .filter(name -> search == null || search.isEmpty() || name.toLowerCase().contains(search.toLowerCase()))
                    .sorted((a, b) -> {
                        if ("name".equalsIgnoreCase(sortBy)) return a.compareToIgnoreCase(b);
                        return a.compareToIgnoreCase(b); // Default fallback
                    })
                    .collect(Collectors.toList());

            // Pagination calculation
            int totalElements = filteredNames.size();
            int totalPages = (int) Math.ceil((double) totalElements / size);
            
            // Validate page bounds
            if (page < 0) page = 0;
            if (page >= totalPages && totalPages > 0) page = totalPages - 1;
            
            int start = page * size;
            int end = Math.min(start + size, totalElements);
            
            List<String> pagedNames = filteredNames.subList(start, end);

            // Fetch metadata ONLY for the current page
            List<io.translab.tantor.server.dto.TopicSummaryDto> content = new ArrayList<>();
            if (!pagedNames.isEmpty()) {
                DescribeTopicsResult describeTopicsResult = client.describeTopics(pagedNames);
                Map<String, TopicDescription> descriptions = describeTopicsResult.allTopicNames().get();

                for (String name : pagedNames) {
                    TopicDescription desc = descriptions.get(name);
                    if (desc != null) {
                        int replicationFactor = desc.partitions().isEmpty() ? 0 : desc.partitions().get(0).replicas().size();
                        long underReplicated = desc.partitions().stream()
                                .filter(p -> p.replicas().size() > p.isr().size())
                                .count();

                        content.add(io.translab.tantor.server.dto.TopicSummaryDto.builder()
                                .name(desc.name())
                                .partitionCount(desc.partitions().size())
                                .replicationFactor(replicationFactor)
                                .underReplicated(underReplicated)
                                .build());
                    }
                }
            }

            return io.translab.tantor.server.dto.PaginatedResponse.<io.translab.tantor.server.dto.TopicSummaryDto>builder()
                    .content(content)
                    .page(page)
                    .size(size)
                    .totalElements(totalElements)
                    .totalPages(totalPages)
                    .hasNext(page < totalPages - 1)
                    .build();

        } catch (InterruptedException | ExecutionException e) {
            log.error("Failed to list topics paginated", e);
            refreshAdminClient(clusterId);
            throw new RuntimeException("Failed to list topics: " + e.getMessage());
        }
    }

    public void createTopic(UUID clusterId, String name, int partitions, short replicationFactor, Map<String, String> configs) {
        AdminClient client = getAdminClient(clusterId);
        try {
            NewTopic newTopic = new NewTopic(name, partitions, replicationFactor).configs(configs != null ? configs : Collections.emptyMap());
            client.createTopics(Collections.singletonList(newTopic)).all().get();
        } catch (InterruptedException | ExecutionException e) {
            log.error("Failed to create topic", e);
            refreshAdminClient(clusterId);
            throw new RuntimeException("Failed to create topic: " + e.getMessage());
        }
    }

    public void deleteTopic(UUID clusterId, String name) {
        AdminClient client = getAdminClient(clusterId);
        try {
            client.deleteTopics(Collections.singletonList(name)).all().get();
        } catch (InterruptedException | ExecutionException e) {
            log.error("Failed to delete topic", e);
            refreshAdminClient(clusterId);
            throw new RuntimeException("Failed to delete topic: " + e.getMessage());
        }
    }

    // --- Consumer Group Operations ---

    public List<Map<String, Object>> listConsumerGroups(UUID clusterId) {
        AdminClient client = getAdminClient(clusterId);
        try {
            Collection<ConsumerGroupListing> listings = client.listConsumerGroups().all().get();
            List<String> groupIds = listings.stream().map(ConsumerGroupListing::groupId).collect(Collectors.toList());
            
            if (groupIds.isEmpty()) return Collections.emptyList();

            Map<String, ConsumerGroupDescription> descriptions = client.describeConsumerGroups(groupIds).all().get();

            return descriptions.values().stream().map(desc -> {
                Map<String, Object> map = new HashMap<>();
                map.put("groupId", desc.groupId());
                map.put("state", desc.state().toString());
                map.put("coordinator", desc.coordinator().host() + ":" + desc.coordinator().port());
                map.put("members", desc.members().size());
                return map;
            }).collect(Collectors.toList());

        } catch (InterruptedException | ExecutionException e) {
            log.error("Failed to list consumer groups", e);
            refreshAdminClient(clusterId);
            throw new RuntimeException("Failed to list consumer groups: " + e.getMessage());
        }
    }

    // --- Configuration Operations ---

    public Map<Integer, Map<String, Object>> getBrokerConfigs(UUID clusterId) {
        AdminClient client = getAdminClient(clusterId);
        try {
            Collection<org.apache.kafka.common.Node> nodes = client.describeCluster().nodes().get();
            List<org.apache.kafka.common.config.ConfigResource> resources = nodes.stream()
                    .map(node -> new org.apache.kafka.common.config.ConfigResource(
                            org.apache.kafka.common.config.ConfigResource.Type.BROKER, String.valueOf(node.id())))
                    .collect(Collectors.toList());

            Map<org.apache.kafka.common.config.ConfigResource, org.apache.kafka.clients.admin.Config> configs = 
                    client.describeConfigs(resources).all().get();

            Map<Integer, Map<String, Object>> result = new HashMap<>();
            configs.forEach((res, conf) -> {
                Map<String, Object> brokerConf = new HashMap<>();
                conf.entries().forEach(entry -> {
                    Map<String, Object> details = new HashMap<>();
                    details.put("value", entry.value());
                    details.put("isReadOnly", entry.isReadOnly());
                    brokerConf.put(entry.name(), details);
                });
                result.put(Integer.parseInt(res.name()), brokerConf);
            });
            return result;
        } catch (InterruptedException | ExecutionException e) {
            log.error("Failed to get broker configs", e);
            refreshAdminClient(clusterId);
            throw new RuntimeException("Failed to get broker configs: " + e.getMessage());
        }
    }

    public void alterBrokerConfig(UUID clusterId, int brokerId, String key, String value) {
        AdminClient client = getAdminClient(clusterId);
        try {
            org.apache.kafka.common.config.ConfigResource resource = new org.apache.kafka.common.config.ConfigResource(
                    org.apache.kafka.common.config.ConfigResource.Type.BROKER, String.valueOf(brokerId));

            org.apache.kafka.clients.admin.AlterConfigOp op = new org.apache.kafka.clients.admin.AlterConfigOp(
                    new org.apache.kafka.clients.admin.ConfigEntry(key, value), 
                    org.apache.kafka.clients.admin.AlterConfigOp.OpType.SET);

            client.incrementalAlterConfigs(Map.of(resource, Collections.singletonList(op))).all().get();
        } catch (InterruptedException | ExecutionException e) {
            log.error("Failed to alter broker config", e);
            refreshAdminClient(clusterId);
            throw new RuntimeException("Failed to alter broker config: " + e.getMessage());
        }
    }
}
