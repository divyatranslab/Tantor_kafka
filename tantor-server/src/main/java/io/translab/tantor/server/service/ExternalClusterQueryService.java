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
import io.translab.tantor.server.service.ExternalClusterService.ExternalAgentTask;

import io.translab.tantor.server.service.ExternalClusterService.ExternalBrokerMetricsDto;

import io.translab.tantor.server.service.ExternalClusterService.ExternalBrokerRecord;
import io.translab.tantor.server.service.ExternalClusterService.ExternalDiscoveryReport;
import io.translab.tantor.server.service.ExternalClusterService.AgentTaskCompletion;
import io.translab.tantor.server.service.ExternalClusterService.BootstrapExternalClusterRequest;
import io.translab.tantor.server.domain.ExternalClusterNode;

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
public class ExternalClusterQueryService {
    private final io.translab.tantor.server.repository.ExternalClusterRepository externalClusterRepository;


    public Optional<ExternalCluster> findExternalCluster(String kafkaClusterId, String name, String bootstrapServers) {
        if (kafkaClusterId != null && !kafkaClusterId.isBlank()) {
            for (ExternalCluster cluster : externalClusterRepository.findByStatusNot("DELETED")) {
                if (ExternalClusterUtil.safeEquals(cluster.getKafkaClusterId(), kafkaClusterId)) {
                    return Optional.of(cluster);
                }
            }
        }
        if (bootstrapServers != null && !bootstrapServers.isBlank()) {
            Optional<ExternalCluster> byBootstrap = externalClusterRepository.findByBootstrapServersAndStatusNot(bootstrapServers.trim(), "DELETED");
            if (byBootstrap.isPresent()) {
                return byBootstrap;
            }
            for (ExternalCluster cluster : externalClusterRepository.findByStatusNot("DELETED")) {
                if (bootstrapServersOverlap(cluster.getBootstrapServers(), bootstrapServers)) {
                    return Optional.of(cluster);
                }
            }
        }
        if (name != null && !name.isBlank()) {
            return externalClusterRepository.findByNameAndStatusNot(name.trim(), "DELETED");
        }
        return Optional.empty();
    }


    public Optional<ExternalCluster> findReusableExternalCluster(String kafkaClusterId, String name, String bootstrapServers) {
        Optional<ExternalCluster> activeCluster = findExternalCluster(kafkaClusterId, name, bootstrapServers);
        if (activeCluster.isPresent()) {
            return activeCluster;
        }
        if (kafkaClusterId != null && !kafkaClusterId.isBlank()) {
            Optional<ExternalCluster> byKafkaId = externalClusterRepository.findByKafkaClusterId(kafkaClusterId.trim());
            if (byKafkaId.isPresent()) {
                return byKafkaId;
            }
        }
        if (bootstrapServers != null && !bootstrapServers.isBlank()) {
            Optional<ExternalCluster> byBootstrap = externalClusterRepository.findByBootstrapServers(bootstrapServers.trim());
            if (byBootstrap.isPresent()) {
                return byBootstrap;
            }
            for (ExternalCluster cluster : externalClusterRepository.findAll()) {
                if (!"DELETED".equalsIgnoreCase(cluster.getStatus())
                        && bootstrapServersOverlap(cluster.getBootstrapServers(), bootstrapServers)) {
                    return Optional.of(cluster);
                }
            }
        }
        if (name != null && !name.isBlank()) {
            return externalClusterRepository.findByName(name.trim());
        }
        return Optional.empty();
    }


    public String mergeBootstrapServers(String existing, String reported) {
        Map<String, Boolean> endpoints = new LinkedHashMap<>();
        for (String value : List.of(ExternalClusterUtil.blankToDefault(existing, ""), ExternalClusterUtil.blankToDefault(reported, ""))) {
            for (String endpoint : value.split(",")) {
                String normalized = endpoint.trim();
                if (!normalized.isBlank()) {
                    endpoints.put(normalized, true);
                }
            }
        }
        return String.join(",", endpoints.keySet());
    }


    public boolean bootstrapServersOverlap(String left, String right) {
        List<String> leftEndpoints = splitBootstrapEndpoints(left);
        List<String> rightEndpoints = splitBootstrapEndpoints(right);
        if (leftEndpoints.isEmpty() || rightEndpoints.isEmpty()) {
            return false;
        }
        for (String leftEndpoint : leftEndpoints) {
            for (String rightEndpoint : rightEndpoints) {
                if (leftEndpoint.equalsIgnoreCase(rightEndpoint)) {
                    return true;
                }
                String leftPort = endpointPort(leftEndpoint);
                String rightPort = endpointPort(rightEndpoint);
                if (!leftPort.isBlank()
                        && leftPort.equals(rightPort)
                        && hostsCompatible(endpointHost(leftEndpoint), endpointHost(rightEndpoint))) {
                    return true;
                }
            }
        }
        return false;
    }


    public List<String> splitBootstrapEndpoints(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> endpoints = new ArrayList<>();
        for (String endpoint : value.split(",")) {
            String normalized = endpoint.trim();
            if (normalized.contains("://")) {
                normalized = normalized.substring(normalized.indexOf("://") + 3);
            }
            if (!normalized.isBlank()) {
                endpoints.add(normalized);
            }
        }
        return endpoints;
    }


    public String endpointHost(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return "";
        }
        String value = endpoint.trim();
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            return end > 0 ? value.substring(1, end) : "";
        }
        int idx = value.lastIndexOf(":");
        return idx > 0 ? value.substring(0, idx) : value;
    }


    public String endpointPort(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return "";
        }
        String value = endpoint.trim();
        int idx = value.lastIndexOf(":");
        return idx > 0 && idx < value.length() - 1 ? value.substring(idx + 1) : "";
    }


    public boolean hostsCompatible(String left, String right) {
        if (left == null || right == null || left.isBlank() || right.isBlank()) {
            return false;
        }
        if (left.equalsIgnoreCase(right)) {
            return true;
        }
        return isWildcardHost(left) || isWildcardHost(right);
    }


    public boolean isWildcardHost(String host) {
        return "localhost".equalsIgnoreCase(host)
                || "0.0.0.0".equals(host)
                || "::".equals(host)
                || "*".equals(host);
    }

}
