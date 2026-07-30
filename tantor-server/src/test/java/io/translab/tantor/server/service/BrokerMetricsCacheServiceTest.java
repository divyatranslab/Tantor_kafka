package io.translab.tantor.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.translab.tantor.server.domain.Cluster;
import io.translab.tantor.server.domain.ClusterServiceAssignment;
import io.translab.tantor.server.domain.Host;
import io.translab.tantor.server.repository.HostRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BrokerMetricsCacheServiceTest {

    @Test
    void includesControllerOnlyNodesAndDoesNotInventDiskCapacity() {
        UUID clusterId = UUID.randomUUID();
        Cluster cluster = new Cluster();
        cluster.setId(clusterId);

        ClusterServiceAssignment broker = assignment(cluster, "broker-host", "broker", 1);
        ClusterServiceAssignment controller = assignment(cluster, "controller-host", "controller", 2);
        cluster.setServices(List.of(broker, controller));

        HostRepository hosts = mock(HostRepository.class);
        HostStatusService status = mock(HostStatusService.class);
        Host brokerHost = host("broker-host");
        Host controllerHost = host("controller-host");
        when(hosts.findById("broker-host")).thenReturn(Optional.of(brokerHost));
        when(hosts.findById("controller-host")).thenReturn(Optional.of(controllerHost));
        when(status.agentConnectivityStatus(brokerHost)).thenReturn("ONLINE");
        when(status.agentConnectivityStatus(controllerHost)).thenReturn("ONLINE");

        BrokerMetricsCacheService service = new BrokerMetricsCacheService(
                hosts, mock(KafkaAdminService.class), mock(ExternalClusterService.class),
                status, new ObjectMapper());

        var rows = service.getBrokerSummaries(cluster);

        assertThat(rows).hasSize(2);
        assertThat(rows).anySatisfy(row -> {
            assertThat(row.getRole()).isEqualTo("controller");
            assertThat(row.isController()).isTrue();
            assertThat(row.getDiskUsedGb()).isNull();
            assertThat(row.getDiskTotalGb()).isNull();
        });
    }

    private static ClusterServiceAssignment assignment(Cluster cluster, String hostId, String role, int nodeId) {
        ClusterServiceAssignment assignment = new ClusterServiceAssignment();
        assignment.setCluster(cluster);
        assignment.setHostId(hostId);
        assignment.setRole(role);
        assignment.setNodeId(nodeId);
        return assignment;
    }

    private static Host host(String id) {
        Host host = new Host();
        host.setId(id);
        host.setHostname(id);
        return host;
    }
}
