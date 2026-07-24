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
import io.translab.tantor.server.service.ExternalClusterService.*;

public class ExternalClusterUtil {

    public static String extractHostFromBootstrap(String bootstrap) {
        if (bootstrap == null || bootstrap.isBlank()) {
            return "";
        }
        String first = bootstrap.split(",")[0].trim();
        if (first.contains("://")) {
            first = first.substring(first.indexOf("://") + 3);
        }
        int idx = first.lastIndexOf(":");
        return idx > 0 ? first.substring(0, idx) : first;
    }


    public static String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() || "null".equalsIgnoreCase(value) ? defaultValue : value;
    }


    public static String firstString(Map<String, Object> values, String... keys) {
        if (values == null) return null;
        for (String key : keys) {
            Object value = values.get(key);
            if (value != null && !String.valueOf(value).isBlank() && !"null".equalsIgnoreCase(String.valueOf(value))) {
                return String.valueOf(value);
            }
        }
        return null;
    }


    public static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }


    public static String normalizeBase64(String value) {
        return value == null ? null : value.replaceAll("\\s", "");
    }


    public static boolean safeEquals(String left, String right) {
        return left != null && right != null && left.equals(right);
    }


    public static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }


    public static int intValue(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }


    public static String taskKey(String clusterName, String hostname, String bootstrap) {
        return blankToDefault(hostname, "") + "|" + blankToDefault(bootstrap, "");
    }


    public static String discoveryKey(ExternalDiscoveryReport report) {
        String source = blankToDefault(report.getKafkaClusterId(), "")
                + "|" + blankToDefault(report.getName(), "")
                + "|" + blankToDefault(report.getHostname(), "")
                + "|" + blankToDefault(report.getBootstrapServers(), "");
        return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8)).toString();
    }


    public static String writeJson(Object value) {
        if (value == null) return null;
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return null;
        }
    }
}