package io.translab.tantor.server.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.translab.tantor.server.audit.AuditService;
import io.translab.tantor.server.domain.Cluster;
import io.translab.tantor.server.repository.ClusterRepository;
import io.translab.tantor.server.repository.ExternalClusterRepository;
import io.translab.tantor.server.repository.HostRepository;
import io.translab.tantor.server.security.EncryptionService;
import io.translab.tantor.server.service.DataServiceConnectionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DataServicesControllerAuditTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void auditsConnectorCreationWithActorClusterAndSelectedTargetWithoutPayloadSecrets() throws Exception {
        server = serverReturning(201, "{}");
        Fixture fixture = fixture();

        fixture.controller.createConnector(fixture.clusterId, null, "http", "127.0.0.1",
                server.getAddress().getPort(), fixture.connectionId,
                fixture.objectMapper.readTree("""
                        {"name":"orders-source","config":{"password":"must-not-be-audited"}}
                        """));

        Map<String, Object> details = capturedDetails(fixture.auditService, "CONNECTOR_CREATED", "SUCCESS");
        assertThat(details)
                .containsEntry("serviceType", "KAFKA_CONNECT")
                .containsEntry("connectionId", fixture.connectionId.toString())
                .containsEntry("targetHost", "127.0.0.1")
                .containsEntry("targetPort", server.getAddress().getPort())
                .containsEntry("connectorName", "orders-source");
        assertThat(details.toString()).doesNotContain("must-not-be-audited");

        verify(fixture.auditService).recordAs(eq("operator-1"), eq("DATA_SERVICES"), isNull(),
                eq("KAFKA_CONNECT"), eq("CONNECTOR_CREATED"), eq("CONNECTOR"), eq("orders-source"),
                eq(fixture.clusterId), eq("SUCCESS"), isNull(), isNull(), isNull(), any());
    }

    @Test
    void auditsSanitizedFailureWhenKafkaConnectRejectsMutation() throws Exception {
        server = serverReturning(500, """
                {"password":"upstream-secret"}
                """);
        Fixture fixture = fixture();

        assertThatThrownBy(() -> fixture.controller.deleteConnector(
                fixture.clusterId, "orders-source", null, "http", "127.0.0.1",
                server.getAddress().getPort(), fixture.connectionId))
                .isInstanceOf(RuntimeException.class);

        Map<String, Object> details = capturedDetails(fixture.auditService, "CONNECTOR_DELETED", "FAILED");
        assertThat(details.get("error")).isEqualTo("The target service returned HTTP 500.");
        assertThat(details.toString()).doesNotContain("upstream-secret");
    }

    @Test
    void auditsSchemaMutationAgainstSelectedRemoteConnection() throws Exception {
        server = serverReturning(200, """
                {"id":42}
                """);
        Fixture fixture = fixture();

        fixture.controller.createSchemaVersion(fixture.clusterId, "orders-value", null,
                "http", "127.0.0.1", server.getAddress().getPort(), fixture.connectionId,
                fixture.objectMapper.readTree("""
                        {"schema":"{}"}
                        """));

        Map<String, Object> details = capturedDetails(fixture.auditService, "SCHEMA_VERSION_CREATED", "SUCCESS");
        assertThat(details)
                .containsEntry("serviceType", "SCHEMA_REGISTRY")
                .containsEntry("subject", "orders-value")
                .containsEntry("connectionId", fixture.connectionId.toString());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> capturedDetails(AuditService auditService, String action, String status) {
        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(auditService).recordAs(anyString(), eq("DATA_SERVICES"), isNull(),
                anyString(), eq(action), anyString(), anyString(), any(UUID.class), eq(status),
                isNull(), isNull(), isNull(), details.capture());
        return details.getValue();
    }

    private Fixture fixture() {
        ClusterRepository clusters = mock(ClusterRepository.class);
        ExternalClusterRepository externalClusters = mock(ExternalClusterRepository.class);
        HostRepository hosts = mock(HostRepository.class);
        DataServiceConnectionService connections = mock(DataServiceConnectionService.class);
        EncryptionService encryption = mock(EncryptionService.class);
        AuditService audit = mock(AuditService.class);
        ObjectMapper mapper = new ObjectMapper();
        UUID clusterId = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();
        Cluster cluster = new Cluster();
        cluster.setId(clusterId);
        when(clusters.findById(clusterId)).thenReturn(Optional.of(cluster));
        when(audit.currentActor()).thenReturn("operator-1");

        DataServicesController controller = new DataServicesController(
                clusters, externalClusters, hosts, mapper, connections, encryption, audit);
        return new Fixture(controller, audit, mapper, clusterId, connectionId);
    }

    private HttpServer serverReturning(int status, String body) throws Exception {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        httpServer.start();
        return httpServer;
    }

    private record Fixture(DataServicesController controller, AuditService auditService,
                           ObjectMapper objectMapper, UUID clusterId, UUID connectionId) {
    }
}
