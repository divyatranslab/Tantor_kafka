package io.translab.tantor.server.service;

import io.translab.tantor.server.domain.Alert;
import io.translab.tantor.server.repository.ActivityLogRepository;
import io.translab.tantor.server.repository.AlertRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActivityAlertServiceTest {

    @Test
    void activatesOneKeyedDegradedAlertAndResolvesLegacyDuplicate() {
        UUID clusterId = UUID.randomUUID();
        AlertRepository repository = mock(AlertRepository.class);
        ActivityAlertService service = new ActivityAlertService(
                mock(ActivityLogRepository.class), repository);

        Alert legacy = alert(null, ActivityAlertService.EXTERNAL_DEGRADED_TITLE, "ACTIVE", clusterId);
        when(repository.findByAlertKey(ActivityAlertService.externalDegradedKey(clusterId)))
                .thenReturn(Optional.empty());
        when(repository.findByClusterIdAndStatusAndTitleIn(
                clusterId,
                "ACTIVE",
                List.of(ActivityAlertService.EXTERNAL_DEGRADED_TITLE,
                        ActivityAlertService.EXTERNAL_FAILED_TITLE,
                        ActivityAlertService.EXTERNAL_AGENT_PARTIAL_TITLE)))
                .thenReturn(List.of(legacy));

        service.synchronizeExternalClusterHealth(clusterId, "payments", "DEGRADED", 0, 1);

        ArgumentCaptor<Alert> saved = ArgumentCaptor.forClass(Alert.class);
        verify(repository, org.mockito.Mockito.times(2)).save(saved.capture());
        Alert active = saved.getAllValues().stream()
                .filter(value -> ActivityAlertService.externalDegradedKey(clusterId)
                        .equals(value.getAlertKey()))
                .findFirst()
                .orElseThrow();
        assertThat(active.getStatus()).isEqualTo("ACTIVE");
        assertThat(active.getSource()).isEqualTo("external_health");
        assertThat(active.getResolvedAt()).isNull();
        assertThat(legacy.getStatus()).isEqualTo("RESOLVED");
        assertThat(legacy.getResolvedAt()).isNotNull();
    }

    @Test
    void resolvesDegradedAndFailedAlertsWhenExternalClusterRecovers() {
        UUID clusterId = UUID.randomUUID();
        AlertRepository repository = mock(AlertRepository.class);
        ActivityAlertService service = new ActivityAlertService(
                mock(ActivityLogRepository.class), repository);

        Alert degraded = alert(
                ActivityAlertService.externalDegradedKey(clusterId),
                ActivityAlertService.EXTERNAL_DEGRADED_TITLE,
                "ACTIVE",
                clusterId);
        Alert failed = alert(
                ActivityAlertService.externalFailedKey(clusterId),
                ActivityAlertService.EXTERNAL_FAILED_TITLE,
                "ACTIVE",
                clusterId);
        when(repository.findByClusterIdAndStatusAndTitleIn(
                clusterId,
                "ACTIVE",
                List.of(ActivityAlertService.EXTERNAL_DEGRADED_TITLE,
                        ActivityAlertService.EXTERNAL_FAILED_TITLE,
                        ActivityAlertService.EXTERNAL_AGENT_PARTIAL_TITLE)))
                .thenReturn(List.of(degraded, failed));

        service.synchronizeExternalClusterHealth(clusterId, "payments", "SUCCESS", 1, 1);

        assertThat(degraded.getStatus()).isEqualTo("RESOLVED");
        assertThat(degraded.getResolvedAt()).isNotNull();
        assertThat(failed.getStatus()).isEqualTo("RESOLVED");
        assertThat(failed.getResolvedAt()).isNotNull();
        verify(repository).save(degraded);
        verify(repository).save(failed);
    }

    @Test
    void resolvesLegacyAlertWhenExternalClusterNoLongerExists() {
        UUID deletedClusterId = UUID.randomUUID();
        UUID activeClusterId = UUID.randomUUID();
        AlertRepository repository = mock(AlertRepository.class);
        ActivityAlertService service = new ActivityAlertService(
                mock(ActivityLogRepository.class), repository);

        Alert orphaned = alert(
                null,
                ActivityAlertService.EXTERNAL_DEGRADED_TITLE,
                "ACTIVE",
                deletedClusterId);
        Alert current = alert(
                ActivityAlertService.externalDegradedKey(activeClusterId),
                ActivityAlertService.EXTERNAL_DEGRADED_TITLE,
                "ACTIVE",
                activeClusterId);
        when(repository.findByStatusAndTitleIn(
                "ACTIVE",
                List.of(ActivityAlertService.EXTERNAL_DEGRADED_TITLE,
                        ActivityAlertService.EXTERNAL_FAILED_TITLE,
                        ActivityAlertService.EXTERNAL_AGENT_PARTIAL_TITLE)))
                .thenReturn(List.of(orphaned, current));

        service.resolveOrphanedExternalClusterHealthAlerts(Set.of(activeClusterId));

        assertThat(orphaned.getStatus()).isEqualTo("RESOLVED");
        assertThat(orphaned.getResolvedAt()).isNotNull();
        assertThat(current.getStatus()).isEqualTo("ACTIVE");
        verify(repository).save(orphaned);
        verify(repository, org.mockito.Mockito.never()).save(current);
    }

    @Test
    void createsExplicitPartialConnectionAlert() {
        UUID clusterId = UUID.randomUUID();
        AlertRepository repository = mock(AlertRepository.class);
        ActivityAlertService service = new ActivityAlertService(
                mock(ActivityLogRepository.class), repository);

        when(repository.findByAlertKey(ActivityAlertService.externalAgentPartialKey(clusterId)))
                .thenReturn(Optional.empty());
        when(repository.findByClusterIdAndStatusAndTitleIn(
                clusterId,
                "ACTIVE",
                List.of(ActivityAlertService.EXTERNAL_DEGRADED_TITLE,
                        ActivityAlertService.EXTERNAL_FAILED_TITLE,
                        ActivityAlertService.EXTERNAL_AGENT_PARTIAL_TITLE)))
                .thenReturn(List.of());

        service.synchronizeExternalClusterHealth(clusterId, "payments", "PARTIAL", 2, 3);

        ArgumentCaptor<Alert> saved = ArgumentCaptor.forClass(Alert.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getTitle()).isEqualTo(ActivityAlertService.EXTERNAL_AGENT_PARTIAL_TITLE);
        assertThat(saved.getValue().getDescription()).contains("2 of 3");
    }

    private Alert alert(String key, String title, String status, UUID clusterId) {
        Alert alert = new Alert();
        alert.setAlertKey(key);
        alert.setTitle(title);
        alert.setSeverity("WARNING");
        alert.setStatus(status);
        alert.setClusterId(clusterId);
        return alert;
    }
}
