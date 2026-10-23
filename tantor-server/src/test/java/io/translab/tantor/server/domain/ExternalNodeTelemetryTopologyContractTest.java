package io.translab.tantor.server.domain;

import io.translab.tantor.server.repository.ExternalClusterNodeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ExternalNodeTelemetryTopologyContractTest {

    @Test
    void telemetryAndTopologyConflictUpdatesPreserveEachOthersColumns() {
        String telemetrySql = repositoryQuery("upsertNodeTelemetry");
        String topologySql = repositoryQuery("upsertTopology");
        String discoveryUpsertSql = repositoryQuery("upsertDiscoveredNode");
        String discoveryUpdateSql = repositoryQuery("updateDiscoveredNode");
        String telemetryUpdate = conflictUpdate(telemetrySql);
        String topologyUpdate = conflictUpdate(topologySql);
        String discoveryUpsert = conflictUpdate(discoveryUpsertSql);

        assertTrue(telemetrySql.contains("null, null"), "telemetry-first inserts must leave roles unknown");
        assertTrue(telemetryUpdate.contains("cpu_usage_pct = case"));
        assertTrue(telemetryUpdate.contains("current_node.last_seen <= excluded.last_seen"));
        assertTrue(telemetryUpdate.contains("messages_in_per_sec = case"));
        assertFalse(telemetryUpdate.contains("is_broker ="));
        assertFalse(telemetryUpdate.contains("is_controller ="));

        assertTrue(topologyUpdate.contains("is_broker = excluded.is_broker"));
        assertTrue(topologyUpdate.contains("is_controller = excluded.is_controller"));
        assertFalse(topologyUpdate.contains("cpu_usage_pct ="));
        assertFalse(topologyUpdate.contains("messages_in_per_sec ="));

        assertTrue(discoveryUpsert.contains("is_broker = excluded.is_broker"));
        assertTrue(discoveryUpsert.contains("current_node.last_seen <= excluded.last_seen"));
        assertTrue(discoveryUpsert.contains("coalesce(excluded.cpu_usage_pct, current_node.cpu_usage_pct)"));
        assertFalse(discoveryUpsert.contains("messages_in_per_sec ="));

        assertTrue(discoveryUpdateSql.contains("is_broker = :isbroker"));
        assertTrue(discoveryUpdateSql.contains("last_seen <= :lastseen"));
        assertTrue(discoveryUpdateSql.contains("coalesce(:cpu, cpu_usage_pct)"));
        assertFalse(discoveryUpdateSql.contains("messages_in_per_sec ="));
    }

    @Test
    void topologyDiscoveryRemovesTheTemporaryExternalInventoryMirror() throws IOException {
        String sql;
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V80__clean_external_node_mirror_on_topology_change.sql")) {
            assertNotNull(stream, "V80 migration resource must exist");
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }

        assertTrue(sql.contains("old_role is distinct from node_role"));
        assertTrue(sql.contains("set node_id = null"));
        assertTrue(sql.contains("where node_id < 0"));
        assertTrue(sql.contains("delete from kf_nodes"));
        assertTrue(sql.contains("role = old_role"));
        assertTrue(sql.contains("not exists"), "co-located nodes sharing a mirror role must remain represented");
        assertTrue(sql.contains("stale.role = 'external'"));
    }

    private String repositoryQuery(String methodName) {
        for (Method method : ExternalClusterNodeRepository.class.getDeclaredMethods()) {
            if (!method.getName().equals(methodName)) {
                continue;
            }
            assertNotNull(method.getAnnotation(Modifying.class));
            Query query = method.getAnnotation(Query.class);
            assertNotNull(query);
            return query.value().toLowerCase();
        }
        throw new AssertionError("Missing repository query: " + methodName);
    }

    private String conflictUpdate(String sql) {
        return sql.substring(sql.indexOf("on conflict"));
    }
}
