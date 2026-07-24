package io.translab.tantor.server.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.translab.tantor.server.domain.Cluster;
import io.translab.tantor.server.domain.ClusterServiceAssignment;
import io.translab.tantor.server.domain.ExternalCluster;
import io.translab.tantor.server.domain.Host;
import io.translab.tantor.server.repository.ClusterRepository;
import io.translab.tantor.server.repository.ExternalClusterRepository;
import io.translab.tantor.server.security.EncryptionService;
import io.translab.tantor.server.repository.HostRepository;
import io.translab.tantor.server.service.DataServiceConnectionService;
import io.translab.tantor.server.dto.ConnectionResponse;
import io.translab.tantor.server.dto.SaveConnectionRequest;
import io.translab.tantor.server.util.SslUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

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
    private final ExternalClusterRepository externalClusterRepository;
    private final HostRepository hostRepository;
    private final ObjectMapper objectMapper;
    private final DataServiceConnectionService dataServiceConnectionService;
    private final EncryptionService encryptionService;    private final HttpClient defaultHttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    // ── HTTP client resolution ───────────────────────────────────────────────

    /**
     * Builds client from a request-scoped X-Custom-Certificate header (backward-compat override).
     * certType may be "PKCS12", the legacy alias "PKCS12_JKS", or "PEM".
     */
    private HttpClient getHttpClient(String encodedCert) {
        if (encodedCert == null || encodedCert.isBlank()) return defaultHttpClient;
        try {
            org.springframework.web.context.request.ServletRequestAttributes attrs =
                (org.springframework.web.context.request.ServletRequestAttributes)
                    org.springframework.web.context.request.RequestContextHolder.currentRequestAttributes();
            String certType = attrs.getRequest().getHeader("X-Custom-Certificate-Type");
            String certPassword = attrs.getRequest().getHeader("X-Custom-Certificate-Password");

            javax.net.ssl.SSLContext sslContext =
                    SslUtils.createSslContext(certType, encodedCert, certPassword);
            return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).sslContext(sslContext).build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize SSL context from provided certificate: " + e.getMessage(), e);
        }
    }

    /**
     * Client resolution order:
     *  1. Request header cert (backward-compat per-request override)
     *  2. Saved connection cert (connectionId validated against clusterId+serviceType)
     *  3. defaultHttpClient
     */
    private HttpClient getHttpClientForCluster(String encodedCert, UUID clusterId,
                                                String serviceType, UUID connectionId) {
        if (encodedCert != null && !encodedCert.isBlank()) return getHttpClient(encodedCert);
        return dataServiceConnectionService.getActiveConnection(clusterId, serviceType, connectionId)
                .map(conn -> {
                    // No certificate configured — use plain HTTP client
                    if (conn.getCertificateData() == null || conn.getCertificateData().isBlank())
                        return defaultHttpClient;
                    // Certificate IS configured — MUST use it. Never silently fall back to the
                    // plain JDK default client, which would fail for self-signed certs with a
                    // misleading SunCertPathBuilderException instead of a clear cert error.
                    try {
                        String pwd = conn.getTruststorePasswordEncrypted() != null
                                ? encryptionService.decrypt(conn.getTruststorePasswordEncrypted()) : null;
                        javax.net.ssl.SSLContext sslContext = SslUtils.createSslContext(
                                conn.getCertificateType(), conn.getCertificateData(), pwd);
                        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).sslContext(sslContext).build();
                    } catch (Exception e) {
                        // Propagate as RuntimeException so caller gets a clear error message
                        // instead of silently connecting without SSL (which fails for self-signed certs).
                        throw new RuntimeException(
                                "Failed to initialize SSL context from saved certificate: " + e.getMessage(), e);
                    }
                })
                .orElse(defaultHttpClient);
    }

    private String callerUsername() {
        return "admin"; // Placeholder — replace with SecurityContextHolder principal
    }

    // ── Persisted connection CRUD ────────────────────────────────────────────

    @PreAuthorize("hasAnyRole('ADMIN', 'MONITOR')")
    @GetMapping("/schema-registry/connections")
    public ResponseEntity<?> listSchemaRegistryConnections(@PathVariable UUID clusterId) {
        return ResponseEntity.ok(dataServiceConnectionService.listConnections(clusterId, "SCHEMA_REGISTRY"));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'MONITOR')")
    @GetMapping("/kafka-connect/connections")
    public ResponseEntity<?> listKafkaConnectConnections(@PathVariable UUID clusterId) {
        return ResponseEntity.ok(dataServiceConnectionService.listConnections(clusterId, "KAFKA_CONNECT"));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/schema-registry/connections/{connectionId}")
    public ResponseEntity<?> deleteSchemaRegistryConnection(
            
            @PathVariable UUID clusterId,
            @PathVariable UUID connectionId) {
        dataServiceConnectionService.deleteConnection(clusterId, "SCHEMA_REGISTRY", connectionId, callerUsername());
        return ResponseEntity.ok(Map.of("message", "Connection deleted successfully"));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/kafka-connect/connections/{connectionId}")
    public ResponseEntity<?> deleteKafkaConnectConnection(
            
            @PathVariable UUID clusterId,
            @PathVariable UUID connectionId) {
        dataServiceConnectionService.deleteConnection(clusterId, "KAFKA_CONNECT", connectionId, callerUsername());
        return ResponseEntity.ok(Map.of("message", "Connection deleted successfully"));
    }


    /** GET single connection — by connectionId if given, else default/first. */
    @PreAuthorize("hasAnyRole('ADMIN', 'MONITOR')")
    @GetMapping("/schema-registry/connection")
    public ResponseEntity<?> getSchemaRegistryConnection(
            @PathVariable UUID clusterId,
            @RequestParam(required = false) UUID connectionId) {
        return dataServiceConnectionService.getConnectionResponse(clusterId, "SCHEMA_REGISTRY", connectionId)
                .<ResponseEntity<?>>map(ResponseEntity::ok).orElse(ResponseEntity.noContent().build());
    }

    /** Create new SR connection, or upsert by name. */
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/schema-registry/connection")
    public ResponseEntity<?> saveSchemaRegistryConnection(
            
            @PathVariable UUID clusterId,
            @RequestBody SaveConnectionRequest req) {
        return ResponseEntity.ok(dataServiceConnectionService.saveConnection(clusterId, "SCHEMA_REGISTRY", req, callerUsername()));
    }

    /** Update existing SR connection by connectionId. Prevents rename creating duplicate rows. */
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/schema-registry/connections/{connectionId}")
    public ResponseEntity<?> updateSchemaRegistryConnectionById(
            
            @PathVariable UUID clusterId,
            @PathVariable UUID connectionId,
            @RequestBody SaveConnectionRequest req) {
        req.setId(connectionId); // inject path param into request so service resolves by id
        return ResponseEntity.ok(dataServiceConnectionService.saveConnection(clusterId, "SCHEMA_REGISTRY", req, callerUsername()));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'MONITOR')")
    @GetMapping("/kafka-connect/connection")
    public ResponseEntity<?> getKafkaConnectConnection(
            @PathVariable UUID clusterId,
            @RequestParam(required = false) UUID connectionId) {
        return dataServiceConnectionService.getConnectionResponse(clusterId, "KAFKA_CONNECT", connectionId)
                .<ResponseEntity<?>>map(ResponseEntity::ok).orElse(ResponseEntity.noContent().build());
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/kafka-connect/connection")
    public ResponseEntity<?> saveKafkaConnectConnection(
            
            @PathVariable UUID clusterId,
            @RequestBody SaveConnectionRequest req) {
        return ResponseEntity.ok(dataServiceConnectionService.saveConnection(clusterId, "KAFKA_CONNECT", req, callerUsername()));
    }

    /** Update existing KC connection by connectionId. */
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/kafka-connect/connections/{connectionId}")
    public ResponseEntity<?> updateKafkaConnectConnectionById(
            
            @PathVariable UUID clusterId,
            @PathVariable UUID connectionId,
            @RequestBody SaveConnectionRequest req) {
        req.setId(connectionId);
        return ResponseEntity.ok(dataServiceConnectionService.saveConnection(clusterId, "KAFKA_CONNECT", req, callerUsername()));
    }

    // ── Legacy URL summary ───────────────────────────────────────────────────

    @PreAuthorize("hasAnyRole('ADMIN', 'MONITOR')")
    @GetMapping("/connections")
    public ResponseEntity<?> connections(@PathVariable UUID clusterId) {
        Cluster cluster = getCluster(clusterId);
        return ResponseEntity.ok(Map.of(
                "schemaRegistryUrl", serviceBaseUrl(cluster, ServiceKind.SCHEMA_REGISTRY),
                "kafkaConnectUrl", serviceBaseUrl(cluster, ServiceKind.KAFKA_CONNECT)
        ));
    }

    // ── Schema Registry live-fetch endpoints ─────────────────────────────────

    /** List subjects and their latest schema metadata. */
    @PreAuthorize("hasAnyRole('ADMIN', 'MONITOR')")
    @GetMapping("/schema-registry/summary")
    public ResponseEntity<?> schemaRegistrySummary(
            @PathVariable UUID clusterId,
            @RequestHeader(value = "X-Custom-Certificate", required = false) String encodedCert,
            @RequestParam(required = false) String protocol,
            @RequestParam(required = false) String ip,
            @RequestParam(required = false) Integer port,
            @RequestParam(required = false) UUID connectionId) {
        Cluster cluster = getCluster(clusterId);
        String baseUrl = customBaseUrl(protocol, ip, port, cluster, ServiceKind.SCHEMA_REGISTRY, connectionId);
        JsonNode subjectsNode = requestJson(baseUrl, "GET", "/subjects", null,
                encodedCert, clusterId, "SCHEMA_REGISTRY", connectionId);

        List<Map<String, Object>> subjects = new ArrayList<>();
        for (JsonNode subjectNode : subjectsNode) {
            String subject = subjectNode.asText();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("subject", subject);
            row.put("type", subjectType(subject));
            try {
                JsonNode latest = requestJson(baseUrl, "GET",
                        "/subjects/" + pathSeg(subject) + "/versions/latest",
                        null, encodedCert, clusterId, "SCHEMA_REGISTRY", connectionId);
                row.put("version", latest.path("version").asInt(0));
                row.put("id", latest.path("id").asInt(0));
                row.put("schemaType", latest.path("schemaType").asText("AVRO"));
                row.put("schema", latest.path("schema").asText(""));
            } catch (RuntimeException ignored) {
                row.put("version", 0); row.put("id", 0); row.put("schemaType", "UNKNOWN"); row.put("schema", "");
            }
            subjects.add(row);
        }

        long keySubjects = subjects.stream().filter(i -> "KEY".equals(i.get("type"))).count();
        long valueSubjects = subjects.stream().filter(i -> "VALUE".equals(i.get("type"))).count();

        return ResponseEntity.ok(Map.of(
                "connection", baseUrl, "subjects", subjects,
                "totalSubjects", subjects.size(), "keySubjects", keySubjects, "valueSubjects", valueSubjects));
    }

    /** Get global compatibility config. */
    @PreAuthorize("hasAnyRole('ADMIN', 'MONITOR')")
    @GetMapping("/schema-registry/config")
    public ResponseEntity<?> getGlobalCompatibility(
            @PathVariable UUID clusterId,
            @RequestHeader(value = "X-Custom-Certificate", required = false) String encodedCert,
            @RequestParam(required = false) String protocol,
            @RequestParam(required = false) String ip,
            @RequestParam(required = false) Integer port,
            @RequestParam(required = false) UUID connectionId) {
        return forwardJson(customBaseUrl(protocol, ip, port, getCluster(clusterId), ServiceKind.SCHEMA_REGISTRY, connectionId),
                "GET", "/config", null, encodedCert, clusterId, "SCHEMA_REGISTRY", connectionId);
    }

    /** Update global compatibility. */
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/schema-registry/config")
    public ResponseEntity<?> updateGlobalCompatibility(
            
            @PathVariable UUID clusterId,
            @RequestHeader(value = "X-Custom-Certificate", required = false) String encodedCert,
            @RequestParam(required = false) String protocol,
            @RequestParam(required = false) String ip,
            @RequestParam(required = false) Integer port,
            @RequestParam(required = false) UUID connectionId,
            @RequestBody JsonNode body) {
        return forwardJson(customBaseUrl(protocol, ip, port, getCluster(clusterId), ServiceKind.SCHEMA_REGISTRY, connectionId),
                "PUT", "/config", body, encodedCert, clusterId, "SCHEMA_REGISTRY", connectionId);
    }

    /** Get all versions for a subject, latest, and compatibility. */
    @PreAuthorize("hasAnyRole('ADMIN', 'MONITOR')")
    @GetMapping("/schema-registry/subjects/{subject}/details")
    public ResponseEntity<?> getSubjectDetails(
            @PathVariable UUID clusterId,
            @PathVariable String subject,
            @RequestHeader(value = "X-Custom-Certificate", required = false) String encodedCert,
            @RequestParam(required = false) String protocol,
            @RequestParam(required = false) String ip,
            @RequestParam(required = false) Integer port,
            @RequestParam(required = false) UUID connectionId) {
        String baseUrl = customBaseUrl(protocol, ip, port, getCluster(clusterId), ServiceKind.SCHEMA_REGISTRY, connectionId);
        String seg = pathSeg(subject);

        JsonNode latest = requestJson(baseUrl, "GET", "/subjects/" + seg + "/versions/latest",
                null, encodedCert, clusterId, "SCHEMA_REGISTRY", connectionId);
        JsonNode versionsNode = requestJson(baseUrl, "GET", "/subjects/" + seg + "/versions",
                null, encodedCert, clusterId, "SCHEMA_REGISTRY", connectionId);

        List<Map<String, Object>> versions = new ArrayList<>();
        for (JsonNode vNode : versionsNode) {
            int v = vNode.asInt();
            try {
                JsonNode vDetail = requestJson(baseUrl, "GET", "/subjects/" + seg + "/versions/" + v,
                        null, encodedCert, clusterId, "SCHEMA_REGISTRY", connectionId);
                versions.add(Map.of(
                        "version", vDetail.path("version").asInt(v),
                        "id", vDetail.path("id").asInt(0),
                        "schemaType", vDetail.path("schemaType").asText("AVRO"),
                        "schema", vDetail.path("schema").asText("")));
            } catch (RuntimeException ignored) {}
        }

        String compatibility = null;
        try {
            JsonNode compatNode = requestJson(baseUrl, "GET", "/config/" + seg,
                    null, encodedCert, clusterId, "SCHEMA_REGISTRY", connectionId);
            if (compatNode != null) {
                compatibility = compatNode.path("compatibilityLevel")
                        .asText(compatNode.path("compatibility").asText(null));
            }
        } catch (RuntimeException ignored) {
            // A missing subject-level override is expected; inherit the global config below.
        }

        if (compatibility == null || compatibility.isBlank()) {
            JsonNode globalCompatNode = requestJson(baseUrl, "GET", "/config",
                    null, encodedCert, clusterId, "SCHEMA_REGISTRY", connectionId);
            compatibility = globalCompatNode.path("compatibilityLevel")
                    .asText(globalCompatNode.path("compatibility").asText("BACKWARD"));
        }

        return ResponseEntity.ok(Map.of(
                "subject", subject,
                "latest", Map.of(
                        "version", latest.path("version").asInt(0),
                        "id", latest.path("id").asInt(0),
                        "schemaType", latest.path("schemaType").asText("AVRO"),
                        "schema", latest.path("schema").asText("")),
                "versions", versions,
                "compatibility", compatibility));
    }

    /** Register a new schema version. */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/schema-registry/subjects/{subject}/versions")
    public ResponseEntity<?> createSchemaVersion(
            
            @PathVariable UUID clusterId,
            @PathVariable String subject,
            @RequestHeader(value = "X-Custom-Certificate", required = false) String encodedCert,
            @RequestParam(required = false) String protocol,
            @RequestParam(required = false) String ip,
            @RequestParam(required = false) Integer port,
            @RequestParam(required = false) UUID connectionId,
            @RequestBody JsonNode body) {
        return forwardJson(customBaseUrl(protocol, ip, port, getCluster(clusterId), ServiceKind.SCHEMA_REGISTRY, connectionId),
                "POST", "/subjects/" + pathSeg(subject) + "/versions",
                body, encodedCert, clusterId, "SCHEMA_REGISTRY", connectionId);
    }

    /** Delete a subject and all its versions. */
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/schema-registry/subjects/{subject}")
    public ResponseEntity<?> deleteSubject(
            
            @PathVariable UUID clusterId,
            @PathVariable String subject,
            @RequestHeader(value = "X-Custom-Certificate", required = false) String encodedCert,
            @RequestParam(required = false) String protocol,
            @RequestParam(required = false) String ip,
            @RequestParam(required = false) Integer port,
            @RequestParam(required = false) UUID connectionId) {
        return forwardJson(customBaseUrl(protocol, ip, port, getCluster(clusterId), ServiceKind.SCHEMA_REGISTRY, connectionId),
                "DELETE", "/subjects/" + pathSeg(subject),
                null, encodedCert, clusterId, "SCHEMA_REGISTRY", connectionId);
    }

    /** Delete a specific schema version. */
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/schema-registry/subjects/{subject}/versions/{version}")
    public ResponseEntity<?> deleteSchemaVersion(
            
            @PathVariable UUID clusterId,
            @PathVariable String subject,
            @PathVariable String version,
            @RequestHeader(value = "X-Custom-Certificate", required = false) String encodedCert,
            @RequestParam(required = false) String protocol,
            @RequestParam(required = false) String ip,
            @RequestParam(required = false) Integer port,
            @RequestParam(required = false) UUID connectionId) {
        return forwardJson(customBaseUrl(protocol, ip, port, getCluster(clusterId), ServiceKind.SCHEMA_REGISTRY, connectionId),
                "DELETE", "/subjects/" + pathSeg(subject) + "/versions/" + version,
                null, encodedCert, clusterId, "SCHEMA_REGISTRY", connectionId);
    }

    /** Update subject-level compatibility. */
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/schema-registry/subjects/{subject}/config")
    public ResponseEntity<?> updateSubjectCompatibility(
            
            @PathVariable UUID clusterId,
            @PathVariable String subject,
            @RequestHeader(value = "X-Custom-Certificate", required = false) String encodedCert,
            @RequestParam(required = false) String protocol,
            @RequestParam(required = false) String ip,
            @RequestParam(required = false) Integer port,
            @RequestParam(required = false) UUID connectionId,
            @RequestBody JsonNode body) {
        return forwardJson(customBaseUrl(protocol, ip, port, getCluster(clusterId), ServiceKind.SCHEMA_REGISTRY, connectionId),
                "PUT", "/config/" + pathSeg(subject),
                body, encodedCert, clusterId, "SCHEMA_REGISTRY", connectionId);
    }

    /** Check schema compatibility against a subject. */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/schema-registry/subjects/{subject}/versions/{version}/compatibility")
    public ResponseEntity<?> checkCompatibility(
            
            @PathVariable UUID clusterId,
            @PathVariable String subject,
            @PathVariable String version,
            @RequestHeader(value = "X-Custom-Certificate", required = false) String encodedCert,
            @RequestParam(required = false) String protocol,
            @RequestParam(required = false) String ip,
            @RequestParam(required = false) Integer port,
            @RequestParam(required = false) UUID connectionId,
            @RequestBody JsonNode body) {
        return forwardJson(customBaseUrl(protocol, ip, port, getCluster(clusterId), ServiceKind.SCHEMA_REGISTRY, connectionId),
                "POST", "/compatibility/subjects/" + pathSeg(subject) + "/versions/" + version,
                body, encodedCert, clusterId, "SCHEMA_REGISTRY", connectionId);
    }

    // ── Kafka Connect live-fetch endpoints ───────────────────────────────────

    /** Summary: version, connector list with status, plugins. */
    @PreAuthorize("hasAnyRole('ADMIN', 'MONITOR')")
    @GetMapping("/kafka-connect/summary")
    public ResponseEntity<?> kafkaConnectSummary(
            @PathVariable UUID clusterId,
            @RequestHeader(value = "X-Custom-Certificate", required = false) String encodedCert,
            @RequestParam(required = false) String protocol,
            @RequestParam(required = false) String ip,
            @RequestParam(required = false) Integer port,
            @RequestParam(required = false) UUID connectionId) {
        Cluster cluster = getCluster(clusterId);
        String baseUrl = customBaseUrl(protocol, ip, port, cluster, ServiceKind.KAFKA_CONNECT, connectionId);

        JsonNode root = requestJson(baseUrl, "GET", "/", null, encodedCert, clusterId, "KAFKA_CONNECT", connectionId);
        JsonNode connectorsNode = requestJson(baseUrl, "GET", "/connectors", null,
                encodedCert, clusterId, "KAFKA_CONNECT", connectionId);
        JsonNode pluginsNode = requestJson(baseUrl, "GET", "/connector-plugins", null,
                encodedCert, clusterId, "KAFKA_CONNECT", connectionId);

        List<Map<String, Object>> connectors = new ArrayList<>();
        int runningTasks = 0, totalTasks = 0, runningConnectors = 0, pausedConnectors = 0, failedConnectors = 0;

        for (JsonNode connNode : connectorsNode) {
            String name = connNode.asText();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", name);
            JsonNode config = requestJson(baseUrl, "GET", "/connectors/" + pathSeg(name) + "/config",
                    null, encodedCert, clusterId, "KAFKA_CONNECT", connectionId);
            JsonNode status = requestJson(baseUrl, "GET", "/connectors/" + pathSeg(name) + "/status",
                    null, encodedCert, clusterId, "KAFKA_CONNECT", connectionId);
            String state = status.path("connector").path("state").asText("UNKNOWN");
            if ("RUNNING".equalsIgnoreCase(state)) runningConnectors++;
            else if ("PAUSED".equalsIgnoreCase(state)) pausedConnectors++;
            else failedConnectors++;

            int cRunning = 0, cTotal = 0;
            for (JsonNode task : status.path("tasks")) {
                cTotal++;
                if ("RUNNING".equalsIgnoreCase(task.path("state").asText())) cRunning++;
            }
            runningTasks += cRunning; totalTasks += cTotal;

            row.put("class", config.path("connector.class").asText(""));
            row.put("state", state); row.put("tasks", cTotal); row.put("runningTasks", cRunning);
            row.put("config", config); row.put("status", status);
            connectors.add(row);
        }

        List<Map<String, Object>> plugins = objectMapper.convertValue(pluginsNode, new TypeReference<>() {});

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("connection", baseUrl);
        result.put("cluster", root);
        result.put("version", root.path("version").asText("Unknown"));
        result.put("connectors", connectors);
        result.put("plugins", plugins);
        result.put("connectorCount", connectors.size());
        result.put("taskCount", totalTasks);
        result.put("runningTasks", runningTasks);
        result.put("runningConnectors", runningConnectors);
        result.put("pausedConnectors", pausedConnectors);
        result.put("failedConnectors", failedConnectors);
        return ResponseEntity.ok(result);
    }

    /** Get connector plugins list. */
    @PreAuthorize("hasAnyRole('ADMIN', 'MONITOR')")
    @GetMapping("/kafka-connect/connector-plugins")
    public ResponseEntity<?> getConnectorPlugins(
            @PathVariable UUID clusterId,
            @RequestHeader(value = "X-Custom-Certificate", required = false) String encodedCert,
            @RequestParam(required = false) String protocol,
            @RequestParam(required = false) String ip,
            @RequestParam(required = false) Integer port,
            @RequestParam(required = false) UUID connectionId) {
        return forwardJson(customBaseUrl(protocol, ip, port, getCluster(clusterId), ServiceKind.KAFKA_CONNECT, connectionId),
                "GET", "/connector-plugins", null, encodedCert, clusterId, "KAFKA_CONNECT", connectionId);
    }

    /** Create connector. */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/kafka-connect/connectors")
    public ResponseEntity<?> createConnector(
            
            @PathVariable UUID clusterId,
            @RequestHeader(value = "X-Custom-Certificate", required = false) String encodedCert,
            @RequestParam(required = false) String protocol,
            @RequestParam(required = false) String ip,
            @RequestParam(required = false) Integer port,
            @RequestParam(required = false) UUID connectionId,
            @RequestBody JsonNode body) {
        return forwardJson(customBaseUrl(protocol, ip, port, getCluster(clusterId), ServiceKind.KAFKA_CONNECT, connectionId),
                "POST", "/connectors", body, encodedCert, clusterId, "KAFKA_CONNECT", connectionId);
    }

    /** Update connector config. */
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/kafka-connect/connectors/{name}/config")
    public ResponseEntity<?> updateConnectorConfig(
            
            @PathVariable UUID clusterId,
            @PathVariable String name,
            @RequestHeader(value = "X-Custom-Certificate", required = false) String encodedCert,
            @RequestParam(required = false) String protocol,
            @RequestParam(required = false) String ip,
            @RequestParam(required = false) Integer port,
            @RequestParam(required = false) UUID connectionId,
            @RequestBody JsonNode body) {
        return forwardJson(customBaseUrl(protocol, ip, port, getCluster(clusterId), ServiceKind.KAFKA_CONNECT, connectionId),
                "PUT", "/connectors/" + pathSeg(name) + "/config",
                body, encodedCert, clusterId, "KAFKA_CONNECT", connectionId);
    }

    /** Pause / resume / restart a connector. */
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/kafka-connect/connectors/{name}/{action:pause|resume|restart}")
    public ResponseEntity<?> connectorAction(
            
            @PathVariable UUID clusterId,
            @PathVariable String name,
            @PathVariable String action,
            @RequestHeader(value = "X-Custom-Certificate", required = false) String encodedCert,
            @RequestParam(required = false) String protocol,
            @RequestParam(required = false) String ip,
            @RequestParam(required = false) Integer port,
            @RequestParam(required = false) UUID connectionId) {
        String upstreamMethod = "restart".equals(action) ? "POST" : "PUT";
        return forwardJson(customBaseUrl(protocol, ip, port, getCluster(clusterId), ServiceKind.KAFKA_CONNECT, connectionId),
                upstreamMethod, "/connectors/" + pathSeg(name) + "/" + action,
                null, encodedCert, clusterId, "KAFKA_CONNECT", connectionId);
    }

    /** Delete a connector. */
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/kafka-connect/connectors/{name}")
    public ResponseEntity<?> deleteConnector(
            
            @PathVariable UUID clusterId,
            @PathVariable String name,
            @RequestHeader(value = "X-Custom-Certificate", required = false) String encodedCert,
            @RequestParam(required = false) String protocol,
            @RequestParam(required = false) String ip,
            @RequestParam(required = false) Integer port,
            @RequestParam(required = false) UUID connectionId) {
        return forwardJson(customBaseUrl(protocol, ip, port, getCluster(clusterId), ServiceKind.KAFKA_CONNECT, connectionId),
                "DELETE", "/connectors/" + pathSeg(name),
                null, encodedCert, clusterId, "KAFKA_CONNECT", connectionId);
    }

    /** Get tasks for a connector. */
    @PreAuthorize("hasAnyRole('ADMIN', 'MONITOR')")
    @GetMapping("/kafka-connect/connectors/{name}/tasks")
    public ResponseEntity<?> getConnectorTasks(
            @PathVariable UUID clusterId,
            @PathVariable String name,
            @RequestHeader(value = "X-Custom-Certificate", required = false) String encodedCert,
            @RequestParam(required = false) String protocol,
            @RequestParam(required = false) String ip,
            @RequestParam(required = false) Integer port,
            @RequestParam(required = false) UUID connectionId) {
        return forwardJson(customBaseUrl(protocol, ip, port, getCluster(clusterId), ServiceKind.KAFKA_CONNECT, connectionId),
                "GET", "/connectors/" + pathSeg(name) + "/tasks",
                null, encodedCert, clusterId, "KAFKA_CONNECT", connectionId);
    }

    /** Restart a specific task. */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/kafka-connect/connectors/{name}/tasks/{taskId}/restart")
    public ResponseEntity<?> restartTask(
            
            @PathVariable UUID clusterId,
            @PathVariable String name,
            @PathVariable String taskId,
            @RequestHeader(value = "X-Custom-Certificate", required = false) String encodedCert,
            @RequestParam(required = false) String protocol,
            @RequestParam(required = false) String ip,
            @RequestParam(required = false) Integer port,
            @RequestParam(required = false) UUID connectionId) {
        return forwardJson(customBaseUrl(protocol, ip, port, getCluster(clusterId), ServiceKind.KAFKA_CONNECT, connectionId),
                "POST", "/connectors/" + pathSeg(name) + "/tasks/" + taskId + "/restart",
                null, encodedCert, clusterId, "KAFKA_CONNECT", connectionId);
    }

    // ── Forwarding / request helpers ─────────────────────────────────────────

    private ResponseEntity<?> forwardJson(String baseUrl, String method, String path,
                                           JsonNode body, String encodedCert,
                                           UUID clusterId, String serviceType, UUID connectionId) {
        return ResponseEntity.ok(requestJson(baseUrl, method, path, body, encodedCert, clusterId, serviceType, connectionId));
    }

    private JsonNode requestJson(String baseUrl, String method, String path, JsonNode body,
                                  String encodedCert, UUID clusterId, String serviceType, UUID connectionId) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(normalizeBaseUrl(baseUrl) + path))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", MediaType.APPLICATION_JSON_VALUE);

            if (body == null) {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                builder.header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .method(method, HttpRequest.BodyPublishers.ofString(
                                objectMapper.writeValueAsString(body)));
            }

            HttpClient client = getHttpClientForCluster(encodedCert, clusterId, serviceType, connectionId);
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new DataServiceException(response.statusCode(), response.body());
            }
            String responseBody = response.body();
            if (responseBody == null || responseBody.isBlank()) return objectMapper.createObjectNode();
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
                .body(Map.of("message", ex.message(), "backendStatus", ex.statusCode()));
    }

    // ── Base URL resolution ──────────────────────────────────────────────────

    /**
     * Resolution order — MUST follow this priority:
     *  1. ?ip= / ?protocol= / ?port= request params → manual override, always wins
     *  2. connectionId param → validated DB lookup for this cluster+serviceType
     *  3. Saved default / first-active DB connection
     *  4. cluster.configJson key lookup
     *  5. host:port default fallback
     */
    private String customBaseUrl(String protocol, String ip, Integer port,
                                  Cluster cluster, ServiceKind kind, UUID connectionId) {
        // PRIORITY 1: manual override params
        String scheme = (protocol != null && !protocol.isBlank()) ? protocol : "http";
        if (ip != null && !ip.isBlank() && port != null) return scheme + "://" + ip + ":" + port;
        if (ip != null && !ip.isBlank())                 return scheme + "://" + ip + ":" + kind.defaultPort();

        // PRIORITY 2+3: DB lookup (by connectionId → default → first active)
        String dbUrl = dataServiceConnectionService
                .resolveBaseUrlFromDb(cluster.getId(), kind.name(), connectionId)
                .orElse(null);
        if (dbUrl != null) return dbUrl;

        // PRIORITY 4+5: configJson → host:port default
        return serviceBaseUrl(cluster, kind);
    }

    private String serviceBaseUrl(Cluster cluster, ServiceKind kind) {
        Map<String, Object> config = readConfig(cluster);
        for (String key : kind.configKeys()) {
            Object value = config.get(key);
            if (value != null && !String.valueOf(value).isBlank())
                return normalizeBaseUrl(String.valueOf(value));
        }
        return "http://" + firstClusterHost(cluster) + ":" + kind.defaultPort();
    }

    // ── Cluster / host helpers ───────────────────────────────────────────────

    private Cluster getCluster(UUID clusterId) {
        return clusterRepository.findById(clusterId).orElseGet(() -> {
            ExternalCluster ext = externalClusterRepository.findById(clusterId).orElse(null);
            if (ext != null) {
                Cluster dummy = new Cluster();
                dummy.setId(ext.getId());
                dummy.setBootstrapServers(ext.getBootstrapServers());
                return dummy;
            }
            throw new IllegalArgumentException("Cluster not found.");
        });
    }

    private String firstClusterHost(Cluster cluster) {
        if (cluster.getServices() != null) {
            for (ClusterServiceAssignment s : cluster.getServices()) {
                String h = resolveHostAddress(s.getHostId());
                if (h != null && !h.isBlank()) return h;
            }
        }
        String bootstrap = cluster.getBootstrapServers();
        if (bootstrap != null && !bootstrap.isBlank()) {
            String first = bootstrap.split(",")[0].trim();
            if (first.contains("://")) first = first.substring(first.indexOf("://") + 3);
            int colon = first.lastIndexOf(':');
            return colon > 0 ? first.substring(0, colon) : first;
        }
        throw new IllegalArgumentException("No host is available for this cluster.");
    }

    private String resolveHostAddress(String hostId) {
        Host host = hostRepository.findById(hostId).orElse(null);
        if (host == null) return hostId;
        if (host.getIpAddresses() != null && !host.getIpAddresses().isBlank()
                && !"[]".equals(host.getIpAddresses())) {
            try {
                List<String> ips = objectMapper.readValue(host.getIpAddresses(), new TypeReference<>() {});
                if (!ips.isEmpty() && ips.get(0) != null && !ips.get(0).isBlank()) return ips.get(0);
            } catch (Exception ignored) {
                String first = host.getIpAddresses().replaceAll("\\[|\\]|\"", "").split(",")[0].trim();
                if (!first.isBlank()) return first;
            }
        }
        return host.getHostname() == null || host.getHostname().isBlank() ? hostId : host.getHostname();
    }

    private Map<String, Object> readConfig(Cluster cluster) {
        if (cluster.getConfigJson() == null || cluster.getConfigJson().isBlank()) return new HashMap<>();
        try { return objectMapper.readValue(cluster.getConfigJson(), new TypeReference<>() {}); }
        catch (Exception e) { return new HashMap<>(); }
    }

    private String normalizeBaseUrl(String baseUrl) {
        String n = baseUrl.trim();
        while (n.endsWith("/")) n = n.substring(0, n.length() - 1);
        return n;
    }

    private String subjectType(String subject) {
        String s = subject.toLowerCase();
        if (s.endsWith("-key")) return "KEY";
        // Schema Registry does not store key/value as subject metadata. This UI creates
        // value schemas by default, so any subject not explicitly named as a key is VALUE.
        return "VALUE";
    }

    private String pathSeg(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private ResponseEntity<?> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Unauthorized"));
    }

    private enum ServiceKind {
        SCHEMA_REGISTRY(8081, List.of(
                "schemaRegistryUrl", "schema_registry_url", "schema.registry.url", "schemaRegistryRestUrl")),
        KAFKA_CONNECT(8083, List.of(
                "kafkaConnectUrl", "kafka_connect_url", "connectRestUrl", "connect_rest_url", "connect.rest.url"));

        private final int defaultPort;
        private final List<String> configKeys;

        ServiceKind(int defaultPort, List<String> configKeys) {
            this.defaultPort = defaultPort;
            this.configKeys = configKeys;
        }

        int defaultPort() { return defaultPort; }
        List<String> configKeys() { return configKeys; }
    }

    private static class DataServiceException extends RuntimeException {
        private final int statusCode;
        private final String body;

        DataServiceException(int statusCode, String body) { super(body); this.statusCode = statusCode; this.body = body; }

        int statusCode() { return statusCode; }
        String message() {
            if (body == null || body.isBlank()) return "The native REST API returned HTTP " + statusCode + ".";
            return "The native REST API returned HTTP " + statusCode + ": " + body;
        }
    }
}
