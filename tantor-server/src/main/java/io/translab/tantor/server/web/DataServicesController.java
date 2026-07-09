package io.translab.tantor.server.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.translab.tantor.server.domain.Cluster;
import io.translab.tantor.server.domain.ClusterServiceAssignment;
import io.translab.tantor.server.domain.Host;
import io.translab.tantor.server.repository.ClusterRepository;
import io.translab.tantor.server.repository.HostRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/clusters/{clusterId}/data-services")
@RequiredArgsConstructor
public class DataServicesController {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);

    private final ClusterRepository clusterRepository;
    private final HostRepository hostRepository;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    @GetMapping("/connections")
    public ResponseEntity<?> connections(@PathVariable UUID clusterId) {
        Cluster cluster = getCluster(clusterId);
        return ResponseEntity.ok(Map.of(
                "schemaRegistryUrl", serviceBaseUrl(cluster, ServiceKind.SCHEMA_REGISTRY),
                "kafkaConnectUrl", serviceBaseUrl(cluster, ServiceKind.KAFKA_CONNECT)
        ));
    }

    @GetMapping("/schema-registry/summary")
    public ResponseEntity<?> schemaRegistrySummary(
            @PathVariable UUID clusterId,
            @RequestParam(required = false) String ip,
            @RequestParam(required = false) Integer port
    ) {
        Cluster cluster = getCluster(clusterId);
        String baseUrl = customBaseUrl(ip, port, cluster, ServiceKind.SCHEMA_REGISTRY);
        JsonNode subjectsNode = requestJson(baseUrl, "GET", "/subjects", null);

        List<Map<String, Object>> subjects = new ArrayList<>();
        for (JsonNode subjectNode : subjectsNode) {
            String subject = subjectNode.asText();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("subject", subject);
            row.put("type", subjectType(subject));
            try {
                JsonNode latest = requestJson(baseUrl, "GET", "/subjects/" + pathSegment(subject) + "/versions/latest", null);
                row.put("version", latest.path("version").asInt(0));
                row.put("id", latest.path("id").asInt(0));
                row.put("schemaType", latest.path("schemaType").asText("AVRO"));
                row.put("schema", latest.path("schema").asText(""));
            } catch (RuntimeException ignored) {
                row.put("version", 0);
                row.put("id", 0);
                row.put("schemaType", "UNKNOWN");
                row.put("schema", "");
            }
            subjects.add(row);
        }

        long keySubjects = subjects.stream().filter(item -> "KEY".equals(item.get("type"))).count();
        long valueSubjects = subjects.stream().filter(item -> "VALUE".equals(item.get("type"))).count();

        return ResponseEntity.ok(Map.of(
                "connection", baseUrl,
                "subjects", subjects,
                "totalSubjects", subjects.size(),
                "keySubjects", keySubjects,
                "valueSubjects", valueSubjects
        ));
    }

    @PostMapping("/schema-registry/subjects/{subject}/versions")
    public ResponseEntity<?> createSchemaVersion(
            @PathVariable UUID clusterId,
            @PathVariable String subject,
            @RequestParam(required = false) String ip,
            @RequestParam(required = false) Integer port,
            @RequestBody JsonNode body
    ) {
        Cluster cluster = getCluster(clusterId);
        return forwardJson(customBaseUrl(ip, port, cluster, ServiceKind.SCHEMA_REGISTRY), "POST",
                "/subjects/" + pathSegment(subject) + "/versions", body);
    }

    @DeleteMapping("/schema-registry/subjects/{subject}")
    public ResponseEntity<?> deleteSubject(
            @PathVariable UUID clusterId,
            @PathVariable String subject,
            @RequestParam(required = false) String ip,
            @RequestParam(required = false) Integer port
    ) {
        Cluster cluster = getCluster(clusterId);
        return forwardJson(customBaseUrl(ip, port, cluster, ServiceKind.SCHEMA_REGISTRY), "DELETE",
                "/subjects/" + pathSegment(subject), null);
    }

    @GetMapping("/kafka-connect/summary")
    public ResponseEntity<?> kafkaConnectSummary(
            @PathVariable UUID clusterId,
            @RequestParam(required = false) String ip,
            @RequestParam(required = false) Integer port
    ) {
        Cluster cluster = getCluster(clusterId);
        String baseUrl = customBaseUrl(ip, port, cluster, ServiceKind.KAFKA_CONNECT);

        JsonNode root = requestJson(baseUrl, "GET", "/", null);
        JsonNode connectorsNode = requestJson(baseUrl, "GET", "/connectors", null);
        JsonNode pluginsNode = requestJson(baseUrl, "GET", "/connector-plugins", null);

        List<Map<String, Object>> connectors = new ArrayList<>();
        int runningTasks = 0;
        int totalTasks = 0;
        int runningConnectors = 0;

        for (JsonNode connectorNode : connectorsNode) {
            String name = connectorNode.asText();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", name);

            JsonNode config = requestJson(baseUrl, "GET", "/connectors/" + pathSegment(name) + "/config", null);
            JsonNode status = requestJson(baseUrl, "GET", "/connectors/" + pathSegment(name) + "/status", null);
            String state = status.path("connector").path("state").asText("UNKNOWN");
            if ("RUNNING".equalsIgnoreCase(state)) {
                runningConnectors++;
            }

            int connectorRunningTasks = 0;
            int connectorTasks = 0;
            for (JsonNode task : status.path("tasks")) {
                connectorTasks++;
                if ("RUNNING".equalsIgnoreCase(task.path("state").asText())) {
                    connectorRunningTasks++;
                }
            }
            runningTasks += connectorRunningTasks;
            totalTasks += connectorTasks;

            row.put("class", config.path("connector.class").asText(""));
            row.put("state", state);
            row.put("tasks", connectorTasks);
            row.put("runningTasks", connectorRunningTasks);
            row.put("config", config);
            row.put("status", status);
            connectors.add(row);
        }

        List<Map<String, Object>> plugins = objectMapper.convertValue(pluginsNode, new TypeReference<>() {});

        return ResponseEntity.ok(Map.of(
                "connection", baseUrl,
                "cluster", root,
                "version", root.path("version").asText("Unknown"),
                "connectors", connectors,
                "plugins", plugins,
                "connectorCount", connectors.size(),
                "taskCount", totalTasks,
                "runningTasks", runningTasks,
                "runningConnectors", runningConnectors
        ));
    }

    @PostMapping("/kafka-connect/connectors")
    public ResponseEntity<?> createConnector(
            @PathVariable UUID clusterId,
            @RequestParam(required = false) String ip,
            @RequestParam(required = false) Integer port,
            @RequestBody JsonNode body
    ) {
        Cluster cluster = getCluster(clusterId);
        return forwardJson(customBaseUrl(ip, port, cluster, ServiceKind.KAFKA_CONNECT), "POST", "/connectors", body);
    }

    @PutMapping("/kafka-connect/connectors/{name}/config")
    public ResponseEntity<?> updateConnectorConfig(
            @PathVariable UUID clusterId,
            @PathVariable String name,
            @RequestParam(required = false) String ip,
            @RequestParam(required = false) Integer port,
            @RequestBody JsonNode body
    ) {
        Cluster cluster = getCluster(clusterId);
        return forwardJson(customBaseUrl(ip, port, cluster, ServiceKind.KAFKA_CONNECT), "PUT",
                "/connectors/" + pathSegment(name) + "/config", body);
    }

    @PutMapping("/kafka-connect/connectors/{name}/{action:pause|resume|restart}")
    public ResponseEntity<?> connectorAction(
            @PathVariable UUID clusterId,
            @PathVariable String name,
            @PathVariable String action,
            @RequestParam(required = false) String ip,
            @RequestParam(required = false) Integer port
    ) {
        Cluster cluster = getCluster(clusterId);
        return forwardJson(customBaseUrl(ip, port, cluster, ServiceKind.KAFKA_CONNECT), "PUT",
                "/connectors/" + pathSegment(name) + "/" + action, null);
    }

    @DeleteMapping("/kafka-connect/connectors/{name}")
    public ResponseEntity<?> deleteConnector(
            @PathVariable UUID clusterId,
            @PathVariable String name,
            @RequestParam(required = false) String ip,
            @RequestParam(required = false) Integer port
    ) {
        Cluster cluster = getCluster(clusterId);
        return forwardJson(customBaseUrl(ip, port, cluster, ServiceKind.KAFKA_CONNECT), "DELETE",
                "/connectors/" + pathSegment(name), null);
    }

    private ResponseEntity<?> forwardJson(String baseUrl, String method, String path, JsonNode body) {
        JsonNode response = requestJson(baseUrl, method, path, body);
        return ResponseEntity.ok(response);
    }

    private JsonNode requestJson(String baseUrl, String method, String path, JsonNode body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(normalizeBaseUrl(baseUrl) + path))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", MediaType.APPLICATION_JSON_VALUE);

            if (body == null) {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                builder.header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .method(method, HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
            }

            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new DataServiceException(response.statusCode(), response.body());
            }
            String responseBody = response.body();
            if (responseBody == null || responseBody.isBlank()) {
                return objectMapper.createObjectNode();
            }
            return objectMapper.readTree(responseBody);
        } catch (DataServiceException e) {
            throw e;
        } catch (IOException e) {
            throw new RuntimeException("Failed to call " + baseUrl + path + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while calling " + baseUrl + path, e);
        }
    }

    @ExceptionHandler(DataServiceException.class)
    public ResponseEntity<Map<String, Object>> handleDataServiceException(DataServiceException ex) {
        return ResponseEntity.status(HttpStatusCode.valueOf(Math.min(Math.max(ex.statusCode(), 400), 599)))
                .body(Map.of(
                        "message", ex.message(),
                        "backendStatus", ex.statusCode()
                ));
    }

    private Cluster getCluster(UUID clusterId) {
        return clusterRepository.findById(clusterId)
                .orElseThrow(() -> new IllegalArgumentException("Cluster not found."));
    }

    private String customBaseUrl(String ip, Integer port, Cluster cluster, ServiceKind kind) {
        if (ip != null && !ip.isBlank() && port != null) {
            return "http://" + ip + ":" + port;
        } else if (ip != null && !ip.isBlank()) {
            return "http://" + ip + ":" + kind.defaultPort();
        }
        return serviceBaseUrl(cluster, kind);
    }

    private String serviceBaseUrl(Cluster cluster, ServiceKind kind) {
        Map<String, Object> config = readConfig(cluster);
        for (String key : kind.configKeys()) {
            Object value = config.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return normalizeBaseUrl(String.valueOf(value));
            }
        }

        String host = firstClusterHost(cluster);
        return "http://" + host + ":" + kind.defaultPort();
    }

    private String firstClusterHost(Cluster cluster) {
        if (cluster.getServices() != null) {
            for (ClusterServiceAssignment service : cluster.getServices()) {
                String host = resolveHostAddress(service.getHostId());
                if (host != null && !host.isBlank()) {
                    return host;
                }
            }
        }

        String bootstrap = cluster.getBootstrapServers();
        if (bootstrap != null && !bootstrap.isBlank()) {
            String first = bootstrap.split(",")[0].trim();
            if (first.contains("://")) {
                first = first.substring(first.indexOf("://") + 3);
            }
            int colon = first.lastIndexOf(':');
            return colon > 0 ? first.substring(0, colon) : first;
        }

        throw new IllegalArgumentException("No host is available for this cluster.");
    }

    private String resolveHostAddress(String hostId) {
        Host host = hostRepository.findById(hostId).orElse(null);
        if (host == null) {
            return hostId;
        }
        if (host.getIpAddresses() != null && !host.getIpAddresses().isBlank() && !"[]".equals(host.getIpAddresses())) {
            try {
                List<String> ips = objectMapper.readValue(host.getIpAddresses(), new TypeReference<>() {});
                if (!ips.isEmpty() && ips.get(0) != null && !ips.get(0).isBlank()) {
                    return ips.get(0);
                }
            } catch (Exception ignored) {
                String first = host.getIpAddresses().replaceAll("\\[|\\]|\"", "").split(",")[0].trim();
                if (!first.isBlank()) {
                    return first;
                }
            }
        }
        return host.getHostname() == null || host.getHostname().isBlank() ? hostId : host.getHostname();
    }

    private Map<String, Object> readConfig(Cluster cluster) {
        if (cluster.getConfigJson() == null || cluster.getConfigJson().isBlank()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(cluster.getConfigJson(), new TypeReference<>() {});
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    private String normalizeBaseUrl(String baseUrl) {
        String normalized = baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String subjectType(String subject) {
        String normalized = subject.toLowerCase();
        if (normalized.endsWith("-key")) {
            return "KEY";
        }
        if (normalized.endsWith("-value")) {
            return "VALUE";
        }
        return "OTHER";
    }

    private String pathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private enum ServiceKind {
        SCHEMA_REGISTRY(8081, List.of(
                "schemaRegistryUrl", "schema_registry_url", "schema.registry.url", "schemaRegistryRestUrl"
        )),
        KAFKA_CONNECT(8083, List.of(
                "kafkaConnectUrl", "kafka_connect_url", "connectRestUrl", "connect_rest_url", "connect.rest.url"
        ));

        private final int defaultPort;
        private final List<String> configKeys;

        ServiceKind(int defaultPort, List<String> configKeys) {
            this.defaultPort = defaultPort;
            this.configKeys = configKeys;
        }

        int defaultPort() {
            return defaultPort;
        }

        List<String> configKeys() {
            return configKeys;
        }
    }

    private static class DataServiceException extends RuntimeException {
        private final int statusCode;
        private final String body;

        DataServiceException(int statusCode, String body) {
            super(body);
            this.statusCode = statusCode;
            this.body = body;
        }

        int statusCode() {
            return statusCode;
        }

        String message() {
            if (body == null || body.isBlank()) {
                return "The native REST API returned HTTP " + statusCode + ".";
            }
            return "The native REST API returned HTTP " + statusCode + ": " + body;
        }
    }
}
