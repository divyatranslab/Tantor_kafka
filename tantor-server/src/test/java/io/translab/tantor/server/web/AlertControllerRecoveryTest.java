package io.translab.tantor.server.web;

import io.translab.tantor.server.domain.Alert;
import io.translab.tantor.server.repository.AlertRepository;
import io.translab.tantor.server.repository.ClusterRepository;
import io.translab.tantor.server.repository.HostParcelRepository;
import io.translab.tantor.server.repository.HostRepository;
import io.translab.tantor.server.repository.TaskRepository;
import io.translab.tantor.server.service.ConsumerLagCacheService;
import io.translab.tantor.server.service.HostStatusService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

class AlertControllerRecoveryTest {

    @Test
    void recoveredRuntimeAlertIsResolvedAndExcludedFromCurrentResponse() {
        AlertRepository alertRepository = mock(AlertRepository.class);
        ClusterRepository clusterRepository = mock(ClusterRepository.class);
        HostRepository hostRepository = mock(HostRepository.class);
        TaskRepository taskRepository = mock(TaskRepository.class);
        HostParcelRepository hostParcelRepository = mock(HostParcelRepository.class);

        Alert recoveredAlert = new Alert();
        recoveredAlert.setAlertKey("host-offline-agent-1");
        recoveredAlert.setSeverity("CRITICAL");
        recoveredAlert.setTitle("Host agent offline");
        recoveredAlert.setStatus("ACTIVE");

        when(clusterRepository.findByStatusNot("DELETED")).thenReturn(List.of());
        when(hostRepository.findAll()).thenReturn(List.of());
        when(taskRepository.findAll()).thenReturn(List.of());
        when(hostParcelRepository.findAll()).thenReturn(List.of());
        when(alertRepository.findByStatusOrderByCreatedAtDesc("ACTIVE"))
                .thenAnswer(ignored -> "ACTIVE".equals(recoveredAlert.getStatus())
                        ? List.of(recoveredAlert)
                        : List.of());

        AlertController controller = new AlertController(
                alertRepository,
                clusterRepository,
                hostRepository,
                taskRepository,
                mock(HostStatusService.class),
                hostParcelRepository,
                mock(ConsumerLagCacheService.class));

        var response = controller.getActiveAlerts();

        assertThat(response.getBody()).isEmpty();
        assertThat(recoveredAlert.getStatus()).isEqualTo("RESOLVED");
        assertThat(recoveredAlert.getResolvedAt()).isNotNull();
        verify(alertRepository).save(recoveredAlert);
    }

    @Test
    void activeExternalHealthAlertIsNotSweptByRuntimeAlertSynchronization() {
        AlertRepository alertRepository = mock(AlertRepository.class);
        ClusterRepository clusterRepository = mock(ClusterRepository.class);
        HostRepository hostRepository = mock(HostRepository.class);
        TaskRepository taskRepository = mock(TaskRepository.class);
        HostParcelRepository hostParcelRepository = mock(HostParcelRepository.class);

        UUID clusterId = UUID.randomUUID();
        Alert externalHealthAlert = new Alert();
        externalHealthAlert.setAlertKey("external-agent-degraded-" + clusterId);
        externalHealthAlert.setSeverity("WARNING");
        externalHealthAlert.setTitle("External Cluster Degraded");
        externalHealthAlert.setClusterId(clusterId);
        externalHealthAlert.setSource("external_health");
        externalHealthAlert.setStatus("ACTIVE");

        when(clusterRepository.findByStatusNot("DELETED")).thenReturn(List.of());
        when(hostRepository.findAll()).thenReturn(List.of());
        when(taskRepository.findAll()).thenReturn(List.of());
        when(hostParcelRepository.findAll()).thenReturn(List.of());
        when(alertRepository.findByStatusOrderByCreatedAtDesc("ACTIVE"))
                .thenReturn(List.of(externalHealthAlert));

        AlertController controller = new AlertController(
                alertRepository,
                clusterRepository,
                hostRepository,
                taskRepository,
                mock(HostStatusService.class),
                hostParcelRepository,
                mock(ConsumerLagCacheService.class));

        var response = controller.getActiveAlerts();

        assertThat(response.getBody()).hasSize(1);
        assertThat(externalHealthAlert.getStatus()).isEqualTo("ACTIVE");
        verify(alertRepository, never()).save(externalHealthAlert);
    }
}
