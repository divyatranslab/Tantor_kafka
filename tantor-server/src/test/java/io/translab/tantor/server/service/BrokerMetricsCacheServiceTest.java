package io.translab.tantor.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.translab.tantor.server.domain.Cluster;
import io.translab.tantor.server.domain.ClusterServiceAssignment;
import io.translab.tantor.server.repository.HostRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BrokerMetricsCacheServiceTest {

    @Test
    void excludesControllerOnlyAssignmentsFromBrokerJmxHealthChecks() {
        HostRepository hostRepository = mock(HostRepository.class);
        KafkaAdminService kafkaAdminService = mock(KafkaAdminService.class);
        ExternalClusterService externalClusterService = mock(ExternalClusterService.class);
        HostStatusService hostStatusService = mock(HostStatusService.class);

        BrokerMetricsCacheService service = new BrokerMetricsCacheService(
                hostRepository,
                kafkaAdminService,
                externalClusterService,
                hostStatusService,
                new ObjectMapper()
        );

        ClusterServiceAssignment controller = assignment("controller-host", "controller");
        ClusterServiceAssignment brokerController = assignment("broker-controller-host", "broker_controller");
        Cluster cluster = new Cluster();
        cluster.setId(UUID.randomUUID());
        cluster.setServices(List.of(controller, brokerController));

        when(hostRepository.findById("broker-controller-host")).thenReturn(Optional.empty());

        service.getBrokerSummaries(cluster);

        verify(hostRepository).findById("broker-controller-host");
        verify(hostRepository, never()).findById("controller-host");
    }

    private static ClusterServiceAssignment assignment(String hostId, String role) {
        ClusterServiceAssignment assignment = new ClusterServiceAssignment();
        assignment.setHostId(hostId);
        assignment.setRole(role);
        return assignment;
    }
}
