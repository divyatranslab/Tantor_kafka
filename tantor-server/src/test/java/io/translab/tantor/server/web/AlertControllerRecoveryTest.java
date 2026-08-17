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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
}
