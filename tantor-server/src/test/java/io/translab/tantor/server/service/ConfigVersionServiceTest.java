package io.translab.tantor.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.translab.tantor.server.audit.AuditService;
import io.translab.tantor.server.domain.*;
import io.translab.tantor.server.persistence.ConfigVersionRepository;
import io.translab.tantor.server.repository.ClusterRepository;
import io.translab.tantor.server.repository.ClusterServiceAssignmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConfigVersionServiceTest {

    @Mock
    private ConfigVersionRepository configVersionRepository;
    @Mock
    private ClusterServiceAssignmentRepository serviceRepository;
    @Mock
    private ClusterRepository clusterRepository;
    @Mock
    private JobService jobService;
    @Mock
    private ActivityAlertService activityAlertService;
    @Mock
    private AuditService auditService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ConfigVersionService service;

    private final UUID clusterId = UUID.randomUUID();
    private final UUID serviceId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ConfigVersionService(
                configVersionRepository,
                serviceRepository,
                clusterRepository,
                jobService,
                activityAlertService,
                objectMapper,
                auditService
        );
    }

    @Test
    void previewProducesAddedRemovedAndModifiedDiff() {
        Map<String, Object> oldConfig = new LinkedHashMap<>(Map.of(
                "num.io.threads", "8",
                "obsolete.key", "yes"
        ));
        Map<String, Object> newConfig = new LinkedHashMap<>(Map.of(
                "num.io.threads", "12",
                "compression.type", "lz4"
        ));

        Map<String, Object> preview = service.preview(oldConfig, newConfig, true);

        assertThat(preview.get("valid")).isEqualTo(true);
        assertThat((java.util.List<?>) preview.get("diff")).hasSize(3);
    }

    @Test
    void previewRejectsTopologyManagedChanges() {
        Map<String, Object> preview = service.preview(
                Map.of("node.id", "1", "num.io.threads", "8"),
                Map.of("node.id", "2", "num.io.threads", "8"),
                true
        );

        assertThat(preview.get("valid")).isEqualTo(false);
        assertThat(String.valueOf(preview.get("errors"))).contains("node.id is topology-managed");
    }

    @Test
    void previewRejectsMinIsrAboveReplicationFactor() {
        Map<String, Object> preview = service.preview(
                Map.of("default.replication.factor", "3", "min.insync.replicas", "2"),
                Map.of("default.replication.factor", "2", "min.insync.replicas", "3"),
                true
        );

        assertThat(preview.get("valid")).isEqualTo(false);
        assertThat(String.valueOf(preview.get("errors"))).contains("cannot be greater");
    }

    @Test
    void normalApply_succeedsAndTransitionsToApplying() {
        Cluster cluster = createCluster(clusterId);
        ClusterServiceAssignment csa = createServiceAssignment(clusterId, serviceId);
        ConfigVersion v1 = createVersion(clusterId, serviceId, 1, ConfigVersionStatus.APPLIED,
                "{}", "{\"num.io.threads\":\"8\"}");
        ConfigVersion v2 = createVersion(clusterId, serviceId, 2, ConfigVersionStatus.VALIDATED,
                "{\"num.io.threads\":\"8\"}", "{\"num.io.threads\":\"16\"}");

        when(configVersionRepository.findById(v2.getId())).thenReturn(Optional.of(v2));
        when(clusterRepository.findById(clusterId)).thenReturn(Optional.of(cluster));
        when(serviceRepository.findByIdForUpdate(serviceId)).thenReturn(Optional.of(csa));
        when(configVersionRepository.findFirstByClusterIdAndServiceIdAndStatusOrderByConfigVersionDesc(
                clusterId, serviceId, ConfigVersionStatus.APPLYING)).thenReturn(Optional.empty());
        when(configVersionRepository.findFirstByClusterIdAndServiceIdAndStatusOrderByConfigVersionDesc(
                clusterId, serviceId, ConfigVersionStatus.APPLIED)).thenReturn(Optional.of(v1));
        when(jobService.createJob(any(Job.class), anyList())).thenAnswer(invocation -> {
            Job job = invocation.getArgument(0);
            job.setId(UUID.randomUUID());
            return job;
        });

        Job job = service.apply(clusterId, v2.getId(), true);

        assertThat(job).isNotNull();
        assertThat(v2.getStatus()).isEqualTo(ConfigVersionStatus.APPLYING);
        assertThat(v2.getJobId()).isEqualTo(job.getId());
        verify(serviceRepository).findByIdForUpdate(serviceId);
        verify(configVersionRepository).save(v2);
    }

    @Test
    void normalRollback_succeedsAndCreatesRollbackVersion() {
        Cluster cluster = createCluster(clusterId);
        ClusterServiceAssignment csa = createServiceAssignment(clusterId, serviceId);
        ConfigVersion v1 = createVersion(clusterId, serviceId, 1, ConfigVersionStatus.SUPERSEDED,
                "{}", "{\"num.io.threads\":\"8\"}");
        ConfigVersion v2 = createVersion(clusterId, serviceId, 2, ConfigVersionStatus.APPLIED,
                "{\"num.io.threads\":\"8\"}", "{\"num.io.threads\":\"16\"}");

        when(configVersionRepository.findById(v1.getId())).thenReturn(Optional.of(v1));
        when(serviceRepository.findByIdForUpdate(serviceId)).thenReturn(Optional.of(csa));
        when(configVersionRepository.findFirstByClusterIdAndServiceIdAndStatusOrderByConfigVersionDesc(
                clusterId, serviceId, ConfigVersionStatus.APPLYING)).thenReturn(Optional.empty());
        when(configVersionRepository.findFirstByClusterIdAndServiceIdAndStatusOrderByConfigVersionDesc(
                clusterId, serviceId, ConfigVersionStatus.APPLIED)).thenReturn(Optional.of(v2));
        when(clusterRepository.findById(clusterId)).thenReturn(Optional.of(cluster));
        when(configVersionRepository.maxVersion(eq(clusterId), eq("host-1"), eq("Broker"), eq("server.properties")))
                .thenReturn(2);
        when(configVersionRepository.save(any(ConfigVersion.class))).thenAnswer(invocation -> {
            ConfigVersion cv = invocation.getArgument(0);
            if (cv.getId() == null) cv.setId(UUID.randomUUID());
            return cv;
        });

        ConfigVersion rollback = service.createRollback(clusterId, v1.getId());

        assertThat(rollback).isNotNull();
        assertThat(rollback.getConfigVersion()).isEqualTo(3);
        assertThat(rollback.getRollbackVersion()).isEqualTo(1);
        assertThat(rollback.getOldConfig()).isEqualTo(v2.getNewConfig());
        assertThat(rollback.getNewConfig()).isEqualTo(v1.getNewConfig());
        verify(serviceRepository).findByIdForUpdate(serviceId);
        verify(configVersionRepository).save(rollback);
    }

    @Test
    void twoConcurrentApplyRequests_cannotBothCommitConflictingActiveVersionTransitions() {
        Cluster cluster = createCluster(clusterId);
        ClusterServiceAssignment csa = createServiceAssignment(clusterId, serviceId);
        ConfigVersion v2 = createVersion(clusterId, serviceId, 2, ConfigVersionStatus.APPLYING,
                "{\"num.io.threads\":\"8\"}", "{\"num.io.threads\":\"16\"}");
        ConfigVersion v3 = createVersion(clusterId, serviceId, 3, ConfigVersionStatus.VALIDATED,
                "{\"num.io.threads\":\"8\"}", "{\"num.io.threads\":\"24\"}");

        when(configVersionRepository.findById(v3.getId())).thenReturn(Optional.of(v3));
        when(clusterRepository.findById(clusterId)).thenReturn(Optional.of(cluster));
        when(serviceRepository.findByIdForUpdate(serviceId)).thenReturn(Optional.of(csa));
        // Another version (v2) is already in APPLYING state
        when(configVersionRepository.findFirstByClusterIdAndServiceIdAndStatusOrderByConfigVersionDesc(
                clusterId, serviceId, ConfigVersionStatus.APPLYING)).thenReturn(Optional.of(v2));

        assertThatThrownBy(() -> service.apply(clusterId, v3.getId(), true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Another configuration version (v2) is currently being applied");

        assertThat(v3.getStatus()).isEqualTo(ConfigVersionStatus.VALIDATED);
        verify(jobService, never()).createJob(any(), anyList());
        verify(configVersionRepository, never()).save(v3);
    }

    @Test
    void concurrentApplyAndRollbackRequests_cannotSilentlyUseAStaleBaseline() {
        ClusterServiceAssignment csa = createServiceAssignment(clusterId, serviceId);
        ConfigVersion v1 = createVersion(clusterId, serviceId, 1, ConfigVersionStatus.SUPERSEDED,
                "{}", "{\"num.io.threads\":\"8\"}");
        ConfigVersion v2 = createVersion(clusterId, serviceId, 2, ConfigVersionStatus.APPLYING,
                "{\"num.io.threads\":\"8\"}", "{\"num.io.threads\":\"16\"}");

        when(configVersionRepository.findById(v1.getId())).thenReturn(Optional.of(v1));
        when(serviceRepository.findByIdForUpdate(serviceId)).thenReturn(Optional.of(csa));
        // An apply is currently running for v2
        when(configVersionRepository.findFirstByClusterIdAndServiceIdAndStatusOrderByConfigVersionDesc(
                clusterId, serviceId, ConfigVersionStatus.APPLYING)).thenReturn(Optional.of(v2));

        assertThatThrownBy(() -> service.createRollback(clusterId, v1.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot create rollback while version v2 is currently being applied");

        verify(configVersionRepository, never()).save(any(ConfigVersion.class));
    }

    @Test
    void staleApplyRequest_rejectedWithoutPartiallyChangingVersionStatusesOrConfiguration() {
        Cluster cluster = createCluster(clusterId);
        ClusterServiceAssignment csa = createServiceAssignment(clusterId, serviceId);
        // Current active version is v3
        ConfigVersion v3 = createVersion(clusterId, serviceId, 3, ConfigVersionStatus.APPLIED,
                "{\"num.io.threads\":\"16\"}", "{\"num.io.threads\":\"24\"}");
        // Candidate version v2 was created from older active config v1
        ConfigVersion v2 = createVersion(clusterId, serviceId, 2, ConfigVersionStatus.VALIDATED,
                "{\"num.io.threads\":\"8\"}", "{\"num.io.threads\":\"16\"}");

        when(configVersionRepository.findById(v2.getId())).thenReturn(Optional.of(v2));
        when(clusterRepository.findById(clusterId)).thenReturn(Optional.of(cluster));
        when(serviceRepository.findByIdForUpdate(serviceId)).thenReturn(Optional.of(csa));
        when(configVersionRepository.findFirstByClusterIdAndServiceIdAndStatusOrderByConfigVersionDesc(
                clusterId, serviceId, ConfigVersionStatus.APPLYING)).thenReturn(Optional.empty());
        when(configVersionRepository.findFirstByClusterIdAndServiceIdAndStatusOrderByConfigVersionDesc(
                clusterId, serviceId, ConfigVersionStatus.APPLIED)).thenReturn(Optional.of(v3));

        assertThatThrownBy(() -> service.apply(clusterId, v2.getId(), true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("This version was created from an older active config");

        // Verify no changes were made
        assertThat(v2.getStatus()).isEqualTo(ConfigVersionStatus.VALIDATED);
        assertThat(csa.getConfigJson()).contains("\"num.io.threads\":\"8\"");
        verify(jobService, never()).createJob(any(), anyList());
        verify(configVersionRepository, never()).save(v2);
    }

    @Test
    void markApplied_cannotSilentlyOverwriteNewerConcurrentState() {
        ClusterServiceAssignment csa = createServiceAssignment(clusterId, serviceId);
        // v2 is in APPLYING state
        ConfigVersion v2 = createVersion(clusterId, serviceId, 2, ConfigVersionStatus.APPLYING,
                "{\"num.io.threads\":\"8\"}", "{\"num.io.threads\":\"16\"}");
        // Meanwhile a newer version v3 was applied concurrently
        ConfigVersion v3 = createVersion(clusterId, serviceId, 3, ConfigVersionStatus.APPLIED,
                "{\"num.io.threads\":\"16\"}", "{\"num.io.threads\":\"24\"}");

        when(configVersionRepository.findById(v2.getId())).thenReturn(Optional.of(v2));
        when(serviceRepository.findByIdForUpdate(serviceId)).thenReturn(Optional.of(csa));
        when(configVersionRepository.findFirstByClusterIdAndServiceIdAndStatusOrderByConfigVersionDesc(
                clusterId, serviceId, ConfigVersionStatus.APPLIED)).thenReturn(Optional.of(v3));

        assertThatThrownBy(() -> service.markApplied(v2.getId(), "{\"properties\":{\"num.io.threads\":\"16\"}}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot mark applied: active version has been updated concurrently");

        // v3 remains APPLIED, not superseded
        assertThat(v3.getStatus()).isEqualTo(ConfigVersionStatus.APPLIED);
        verify(configVersionRepository, never()).save(v3);
        verify(configVersionRepository, never()).save(v2);
    }

    @Test
    void markApplied_idempotentWhenAlreadyApplied() {
        ConfigVersion v2 = createVersion(clusterId, serviceId, 2, ConfigVersionStatus.APPLIED,
                "{\"num.io.threads\":\"8\"}", "{\"num.io.threads\":\"16\"}");
        when(configVersionRepository.findById(v2.getId())).thenReturn(Optional.of(v2));

        service.markApplied(v2.getId(), "{\"properties\":{\"num.io.threads\":\"16\"}}");

        // Should return cleanly without exceptions or updating service
        verify(serviceRepository, never()).findByIdForUpdate(any());
        verify(serviceRepository, never()).save(any());
    }

    @Test
    void markApplied_rejectsVersionNotInApplyingState() {
        ConfigVersion v2 = createVersion(clusterId, serviceId, 2, ConfigVersionStatus.VALIDATED,
                "{\"num.io.threads\":\"8\"}", "{\"num.io.threads\":\"16\"}");
        when(configVersionRepository.findById(v2.getId())).thenReturn(Optional.of(v2));

        assertThatThrownBy(() -> service.markApplied(v2.getId(), "{}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Version must be in APPLYING state");

        verify(serviceRepository, never()).findByIdForUpdate(any());
    }

    @Test
    void multiThreadedApply_serializesAndRejectsConcurrentConflict() throws Exception {
        Cluster cluster = createCluster(clusterId);
        ClusterServiceAssignment csa = createServiceAssignment(clusterId, serviceId);
        ConfigVersion v1 = createVersion(clusterId, serviceId, 1, ConfigVersionStatus.APPLIED,
                "{}", "{\"num.io.threads\":\"8\"}");
        ConfigVersion v2 = createVersion(clusterId, serviceId, 2, ConfigVersionStatus.VALIDATED,
                "{\"num.io.threads\":\"8\"}", "{\"num.io.threads\":\"16\"}");
        ConfigVersion v3 = createVersion(clusterId, serviceId, 3, ConfigVersionStatus.VALIDATED,
                "{\"num.io.threads\":\"8\"}", "{\"num.io.threads\":\"24\"}");

        when(configVersionRepository.findById(v2.getId())).thenReturn(Optional.of(v2));
        when(configVersionRepository.findById(v3.getId())).thenReturn(Optional.of(v3));
        when(clusterRepository.findById(clusterId)).thenReturn(Optional.of(cluster));
        when(jobService.createJob(any(Job.class), anyList())).thenAnswer(invocation -> {
            Job job = invocation.getArgument(0);
            job.setId(UUID.randomUUID());
            return job;
        });

        // Simulate database pessimistic serialization via atomic state:
        // When v2 applies first, status transitions to APPLYING
        AtomicBoolean v2Applying = new AtomicBoolean(false);
        when(serviceRepository.findByIdForUpdate(serviceId)).thenAnswer(inv -> {
            Thread.sleep(30); // simulate DB lock window
            return Optional.of(csa);
        });
        when(configVersionRepository.findFirstByClusterIdAndServiceIdAndStatusOrderByConfigVersionDesc(
                clusterId, serviceId, ConfigVersionStatus.APPLYING)).thenAnswer(inv ->
                v2Applying.get() ? Optional.of(v2) : Optional.empty());
        when(configVersionRepository.findFirstByClusterIdAndServiceIdAndStatusOrderByConfigVersionDesc(
                clusterId, serviceId, ConfigVersionStatus.APPLIED)).thenReturn(Optional.of(v1));
        when(configVersionRepository.save(v2)).thenAnswer(inv -> {
            v2Applying.set(true);
            return v2;
        });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);

        Future<Job> futureV2 = executor.submit(() -> {
            startGate.await();
            return service.apply(clusterId, v2.getId(), true);
        });

        Future<Job> futureV3 = executor.submit(() -> {
            startGate.await();
            Thread.sleep(15); // slight stagger into the locked window
            return service.apply(clusterId, v3.getId(), true);
        });

        startGate.countDown();

        Job jobV2 = futureV2.get(5, TimeUnit.SECONDS);
        assertThat(jobV2).isNotNull();
        assertThat(v2.getStatus()).isEqualTo(ConfigVersionStatus.APPLYING);

        // v3 must fail because v2 was already transitioned to APPLYING
        ExecutionException ex = null;
        try {
            futureV3.get(5, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            ex = e;
        }
        assertThat(ex).isNotNull();
        assertThat(ex.getCause()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Another configuration version (v2) is currently being applied");

        executor.shutdown();
    }

    private Cluster createCluster(UUID clusterId) {
        Cluster cluster = new Cluster();
        cluster.setId(clusterId);
        cluster.setEnvironment("DEV");
        cluster.setConfigJson("{\"num.io.threads\":\"8\"}");
        return cluster;
    }

    private ClusterServiceAssignment createServiceAssignment(UUID clusterId, UUID serviceId) {
        ClusterServiceAssignment csa = new ClusterServiceAssignment();
        csa.setId(serviceId);
        Cluster cluster = new Cluster();
        cluster.setId(clusterId);
        csa.setCluster(cluster);
        csa.setHostId("host-1");
        csa.setRole("Broker");
        csa.setConfigJson("{\"properties\":{\"num.io.threads\":\"8\"}}");
        return csa;
    }

    private ConfigVersion createVersion(UUID clusterId, UUID serviceId, int versionNum, ConfigVersionStatus status,
                                        String oldConfigJson, String newConfigJson) {
        ConfigVersion cv = new ConfigVersion();
        cv.setId(UUID.randomUUID());
        cv.setClusterId(clusterId);
        cv.setServiceId(serviceId);
        cv.setHostId("host-1");
        cv.setComponent("Broker");
        cv.setConfigFileName("server.properties");
        cv.setConfigVersion(versionNum);
        cv.setStatus(status);
        cv.setOldConfig(oldConfigJson);
        cv.setNewConfig(newConfigJson);
        cv.setApprovalRequired(false);
        return cv;
    }
}
