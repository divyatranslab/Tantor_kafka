package io.translab.tantor.server.service;

import io.translab.tantor.server.audit.AuditService;
import io.translab.tantor.server.repository.ClusterRepository;
import io.translab.tantor.server.service.TopicRecreationException.TopicPresence;
import io.translab.tantor.server.util.RoleAuthenticationUtil;
import io.translab.tantor.server.web.ApiError;
import io.translab.tantor.server.web.ApiExceptionHandler;
import io.translab.tantor.server.web.TopicsController;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.errors.TopicExistsException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TopicOperationsServiceRecreationTest {

    @Mock
    private KafkaAdminService kafkaAdminService;
    @Mock
    private AuditService auditService;
    @Mock
    private AdminClient adminClient;

    private TopicOperationsService service;
    private final UUID clusterId = UUID.randomUUID();
    private final String topicName = "orders-topic";

    @BeforeEach
    void setUp() {
        service = spy(new TopicOperationsService(kafkaAdminService, auditService));
        when(kafkaAdminService.getAdminClient(clusterId)).thenReturn(adminClient);
    }

    @AfterEach
    void tearDown() {
        Thread.interrupted();
    }

    private void setupTopicMetadata(boolean isInternal, List<TopicPartitionInfo> partitions, Map<String, String> configs, List<Node> activeNodes) {
        TopicDescription description = new TopicDescription(topicName, isInternal, partitions);
        DescribeTopicsResult describeTopicsResult = mock(DescribeTopicsResult.class);
        when(describeTopicsResult.allTopicNames()).thenReturn(KafkaFuture.completedFuture(Map.of(topicName, description)));
        when(adminClient.describeTopics(List.of(topicName))).thenReturn(describeTopicsResult);

        ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, topicName);
        List<ConfigEntry> entries = configs.entrySet().stream()
                .map(e -> new ConfigEntry(e.getKey(), e.getValue()))
                .toList();
        Config config = new Config(entries);
        DescribeConfigsResult describeConfigsResult = mock(DescribeConfigsResult.class);
        when(describeConfigsResult.all()).thenReturn(KafkaFuture.completedFuture(Map.of(resource, config)));
        when(adminClient.describeConfigs(anyCollection(), any(DescribeConfigsOptions.class))).thenReturn(describeConfigsResult);

        DescribeClusterResult describeClusterResult = mock(DescribeClusterResult.class);
        when(describeClusterResult.nodes()).thenReturn(KafkaFuture.completedFuture(activeNodes));
        when(adminClient.describeCluster()).thenReturn(describeClusterResult);
    }

    @Test
    void checkTopicPresence_returnsPresentWhenTopicExists() {
        ListTopicsResult listTopicsResult = mock(ListTopicsResult.class);
        when(listTopicsResult.names()).thenReturn(KafkaFuture.completedFuture(Set.of("other-topic", topicName)));
        when(adminClient.listTopics(any(ListTopicsOptions.class))).thenReturn(listTopicsResult);

        assertThat(service.checkTopicPresence(adminClient, topicName)).isEqualTo(TopicPresence.PRESENT);
        assertThat(service.checkTopicExists(adminClient, topicName)).isTrue();
    }

    @Test
    void checkTopicPresence_returnsAbsentWhenTopicMissing() {
        ListTopicsResult listTopicsResult = mock(ListTopicsResult.class);
        when(listTopicsResult.names()).thenReturn(KafkaFuture.completedFuture(Set.of("other-topic")));
        when(adminClient.listTopics(any(ListTopicsOptions.class))).thenReturn(listTopicsResult);

        assertThat(service.checkTopicPresence(adminClient, topicName)).isEqualTo(TopicPresence.ABSENT);
        assertThat(service.checkTopicExists(adminClient, topicName)).isFalse();
    }

    @Test
    void checkTopicPresence_returnsUnknownOnTimeoutOrError() throws Exception {
        ListTopicsResult listTopicsResult = mock(ListTopicsResult.class);
        KafkaFuture<Set<String>> future = mock(KafkaFuture.class);
        when(future.get(anyLong(), any())).thenThrow(new ExecutionException(new org.apache.kafka.common.errors.TimeoutException("Kafka timeout")));
        when(listTopicsResult.names()).thenReturn(future);
        when(adminClient.listTopics(any(ListTopicsOptions.class))).thenReturn(listTopicsResult);

        assertThat(service.checkTopicPresence(adminClient, topicName)).isEqualTo(TopicPresence.UNKNOWN);
        assertThat(service.checkTopicExists(adminClient, topicName)).isFalse();
    }

    @Test
    void checkTopicPresence_restoresInterruptFlagOnInterruption() throws Exception {
        ListTopicsResult listTopicsResult = mock(ListTopicsResult.class);
        KafkaFuture<Set<String>> future = mock(KafkaFuture.class);
        when(future.get(anyLong(), any())).thenThrow(new InterruptedException("Interrupted"));
        when(listTopicsResult.names()).thenReturn(future);
        when(adminClient.listTopics(any(ListTopicsOptions.class))).thenReturn(listTopicsResult);

        assertThat(service.checkTopicPresence(adminClient, topicName)).isEqualTo(TopicPresence.UNKNOWN);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    void recreateTopic_rejectsInternalTopicBeforeDeletion() {
        Node n1 = new Node(1, "host1", 9092);
        TopicPartitionInfo p0 = new TopicPartitionInfo(0, n1, List.of(n1), List.of(n1));
        setupTopicMetadata(true, List.of(p0), Map.of(), List.of(n1));

        assertThatThrownBy(() -> service.recreateTopic(clusterId, topicName))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Internal Kafka topic '" + topicName + "' cannot be recreated");

        verify(adminClient, never()).deleteTopics(anyCollection());
        verify(adminClient, never()).createTopics(anyCollection());
    }

    @Test
    void recreateTopic_rejectsTopicWithEmptyPartitions() {
        Node n1 = new Node(1, "host1", 9092);
        setupTopicMetadata(false, List.of(), Map.of(), List.of(n1));

        assertThatThrownBy(() -> service.recreateTopic(clusterId, topicName))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has no partition metadata");

        verify(adminClient, never()).deleteTopics(anyCollection());
        verify(adminClient, never()).createTopics(anyCollection());
    }

    @Test
    void recreateTopic_rejectsOfflineBrokerInReplicaAssignments() {
        Node n1 = new Node(1, "host1", 9092);
        Node n2Offline = new Node(2, "host2", 9092);
        TopicPartitionInfo p0 = new TopicPartitionInfo(0, n1, List.of(n1, n2Offline), List.of(n1));
        setupTopicMetadata(false, List.of(p0), Map.of(), List.of(n1));

        assertThatThrownBy(() -> service.recreateTopic(clusterId, topicName))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("references broker 2 which is not active in the cluster");

        verify(adminClient, never()).deleteTopics(anyCollection());
        verify(adminClient, never()).createTopics(anyCollection());
    }

    @Test
    void recreateTopic_rejectsInconsistentReplicationFactors() {
        Node n1 = new Node(1, "host1", 9092);
        Node n2 = new Node(2, "host2", 9092);
        TopicPartitionInfo p0 = new TopicPartitionInfo(0, n1, List.of(n1, n2), List.of(n1, n2));
        TopicPartitionInfo p1 = new TopicPartitionInfo(1, n1, List.of(n1), List.of(n1));
        setupTopicMetadata(false, List.of(p0, p1), Map.of(), List.of(n1, n2));

        assertThatThrownBy(() -> service.recreateTopic(clusterId, topicName))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inconsistent replication factors");

        verify(adminClient, never()).deleteTopics(anyCollection());
        verify(adminClient, never()).createTopics(anyCollection());
    }

    @Test
    void recreateTopic_handlesDeletionFailureGracefully() throws Exception {
        Node n1 = new Node(1, "host1", 9092);
        TopicPartitionInfo p0 = new TopicPartitionInfo(0, n1, List.of(n1), List.of(n1));
        setupTopicMetadata(false, List.of(p0), Map.of("retention.ms", "86400000"), List.of(n1));

        DeleteTopicsResult deleteTopicsResult = mock(DeleteTopicsResult.class);
        KafkaFuture<Void> deleteFuture = mock(KafkaFuture.class);
        when(deleteFuture.get(anyLong(), any())).thenThrow(new ExecutionException(new org.apache.kafka.common.errors.TimeoutException("Kafka timeout")));
        when(deleteTopicsResult.all()).thenReturn(deleteFuture);
        when(adminClient.deleteTopics(List.of(topicName))).thenReturn(deleteTopicsResult);

        assertThatThrownBy(() -> service.recreateTopic(clusterId, topicName))
                .isInstanceOf(TopicRecreationException.class)
                .satisfies(ex -> {
                    TopicRecreationException tre = (TopicRecreationException) ex;
                    assertThat(tre.getStage()).isEqualTo("DELETION_FAILED");
                    assertThat(tre.getPresence()).isEqualTo(TopicPresence.PRESENT);
                });

        verify(adminClient, never()).createTopics(anyCollection());
        verify(auditService).record(eq("DATA_SERVICES"), eq("TOPIC_RECREATE_FAILED"), eq("TOPIC"), eq(topicName),
                eq(clusterId), eq("FAILED"), isNull(), isNull(), isNull(), any());
    }

    @Test
    void recreateTopic_restoresInterruptFlagOnInterruption() throws Exception {
        Node n1 = new Node(1, "host1", 9092);
        TopicPartitionInfo p0 = new TopicPartitionInfo(0, n1, List.of(n1), List.of(n1));
        setupTopicMetadata(false, List.of(p0), Map.of(), List.of(n1));

        DeleteTopicsResult deleteTopicsResult = mock(DeleteTopicsResult.class);
        KafkaFuture<Void> deleteFuture = mock(KafkaFuture.class);
        when(deleteFuture.get(anyLong(), any())).thenThrow(new InterruptedException("Interrupted during deletion"));
        when(deleteTopicsResult.all()).thenReturn(deleteFuture);
        when(adminClient.deleteTopics(List.of(topicName))).thenReturn(deleteTopicsResult);

        assertThatThrownBy(() -> service.recreateTopic(clusterId, topicName))
                .isInstanceOf(TopicRecreationException.class)
                .satisfies(ex -> {
                    TopicRecreationException tre = (TopicRecreationException) ex;
                    assertThat(tre.getStage()).isEqualTo("DELETION_INTERRUPTED");
                    assertThat(tre.getPresence()).isEqualTo(TopicPresence.PRESENT);
                });

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    void recreateTopic_doesNotTriggerCreationWhenTopicStateIsUnknown() throws Exception {
        Node n1 = new Node(1, "host1", 9092);
        TopicPartitionInfo p0 = new TopicPartitionInfo(0, n1, List.of(n1), List.of(n1));
        setupTopicMetadata(false, List.of(p0), Map.of(), List.of(n1));

        DeleteTopicsResult deleteTopicsResult = mock(DeleteTopicsResult.class);
        when(deleteTopicsResult.all()).thenReturn(KafkaFuture.completedFuture(null));
        when(adminClient.deleteTopics(List.of(topicName))).thenReturn(deleteTopicsResult);

        doNothing().when(service).waitForTopicPresence(adminClient, topicName, false, 20_000);
        // Topic presence check fails / returns UNKNOWN
        doReturn(TopicPresence.UNKNOWN).when(service).checkTopicPresence(adminClient, topicName);

        assertThatThrownBy(() -> service.recreateTopic(clusterId, topicName))
                .isInstanceOf(TopicRecreationException.class)
                .satisfies(ex -> {
                    TopicRecreationException tre = (TopicRecreationException) ex;
                    assertThat(tre.getStage()).isEqualTo("RECREATION_FAILED");
                    // Must report UNKNOWN, NOT ABSENT!
                    assertThat(tre.getPresence()).isEqualTo(TopicPresence.UNKNOWN);
                });

        // Ensure creation was NEVER attempted because presence was unknown
        verify(adminClient, never()).createTopics(anyCollection());
    }

    @Test
    void recreateTopic_reportsUnknownPresenceWhenKafkaFailsAfterCreationFailure() throws Exception {
        Node n1 = new Node(1, "host1", 9092);
        TopicPartitionInfo p0 = new TopicPartitionInfo(0, n1, List.of(n1), List.of(n1));
        setupTopicMetadata(false, List.of(p0), Map.of(), List.of(n1));

        DeleteTopicsResult deleteTopicsResult = mock(DeleteTopicsResult.class);
        when(deleteTopicsResult.all()).thenReturn(KafkaFuture.completedFuture(null));
        when(adminClient.deleteTopics(List.of(topicName))).thenReturn(deleteTopicsResult);

        doNothing().when(service).waitForTopicPresence(adminClient, topicName, false, 20_000);

        // Pre-creation check says ABSENT so creation is attempted
        // Post-creation check returns UNKNOWN because Kafka metadata lookup failed
        doReturn(TopicPresence.ABSENT)
                .doReturn(TopicPresence.UNKNOWN)
                .when(service).checkTopicPresence(adminClient, topicName);

        CreateTopicsResult createFail = mock(CreateTopicsResult.class);
        KafkaFuture<Void> createFailFuture = mock(KafkaFuture.class);
        when(createFailFuture.get(anyLong(), any())).thenThrow(new ExecutionException(
                new org.apache.kafka.common.errors.PolicyViolationException("Quota exceeded")));
        when(createFail.all()).thenReturn(createFailFuture);
        when(adminClient.createTopics(anyCollection())).thenReturn(createFail);

        assertThatThrownBy(() -> service.recreateTopic(clusterId, topicName))
                .isInstanceOf(TopicRecreationException.class)
                .satisfies(ex -> {
                    TopicRecreationException tre = (TopicRecreationException) ex;
                    assertThat(tre.getStage()).isEqualTo("RECREATION_FAILED");
                    // Must report UNKNOWN, NOT ABSENT
                    assertThat(tre.getPresence()).isEqualTo(TopicPresence.UNKNOWN);
                });
    }

    @Test
    void recreateTopic_executesFullHappyPath() throws Exception {
        Node n1 = new Node(1, "host1", 9092);
        TopicPartitionInfo p0 = new TopicPartitionInfo(0, n1, List.of(n1), List.of(n1));
        setupTopicMetadata(false, List.of(p0), Map.of("retention.ms", "86400000"), List.of(n1));

        DeleteTopicsResult deleteTopicsResult = mock(DeleteTopicsResult.class);
        when(deleteTopicsResult.all()).thenReturn(KafkaFuture.completedFuture(null));
        when(adminClient.deleteTopics(List.of(topicName))).thenReturn(deleteTopicsResult);

        doNothing().when(service).waitForTopicPresence(adminClient, topicName, false, 20_000);
        doNothing().when(service).waitForTopicPresence(adminClient, topicName, true, 15_000);
        doReturn(TopicPresence.ABSENT).when(service).checkTopicPresence(adminClient, topicName);

        CreateTopicsResult createTopicsResult = mock(CreateTopicsResult.class);
        when(createTopicsResult.all()).thenReturn(KafkaFuture.completedFuture(null));
        when(adminClient.createTopics(anyCollection())).thenReturn(createTopicsResult);

        service.recreateTopic(clusterId, topicName);

        ArgumentCaptor<Collection<NewTopic>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(adminClient).createTopics(captor.capture());
        NewTopic createdTopic = captor.getValue().iterator().next();
        assertThat(createdTopic.name()).isEqualTo(topicName);
        assertThat(createdTopic.replicasAssignments()).containsEntry(0, List.of(1));
        assertThat(createdTopic.configs()).containsEntry("retention.ms", "86400000");

        verify(auditService).record(eq("DATA_SERVICES"), eq("TOPIC_RECREATE_INITIATED"), eq("TOPIC"), eq(topicName),
                eq(clusterId), eq("IN_PROGRESS"), isNull(), isNull(), isNull(), any());
        verify(auditService).record(eq("DATA_SERVICES"), eq("TOPIC_RECREATE_COMPLETED"), eq("TOPIC"), eq(topicName),
                eq(clusterId), eq("SUCCESS"), isNull(), isNull(), isNull(), any());
    }

    @Test
    void recreateTopic_retriesWhenTopicDeletionInProgressAndSucceeds() throws Exception {
        Node n1 = new Node(1, "host1", 9092);
        TopicPartitionInfo p0 = new TopicPartitionInfo(0, n1, List.of(n1), List.of(n1));
        setupTopicMetadata(false, List.of(p0), Map.of(), List.of(n1));

        DeleteTopicsResult deleteTopicsResult = mock(DeleteTopicsResult.class);
        when(deleteTopicsResult.all()).thenReturn(KafkaFuture.completedFuture(null));
        when(adminClient.deleteTopics(List.of(topicName))).thenReturn(deleteTopicsResult);

        doNothing().when(service).waitForTopicPresence(adminClient, topicName, false, 20_000);
        doNothing().when(service).waitForTopicPresence(adminClient, topicName, true, 15_000);
        doReturn(TopicPresence.ABSENT).when(service).checkTopicPresence(adminClient, topicName);

        CreateTopicsResult createFail = mock(CreateTopicsResult.class);
        KafkaFuture<Void> createFailFuture = mock(KafkaFuture.class);
        when(createFailFuture.get(anyLong(), any())).thenThrow(new ExecutionException(
                new TopicExistsException("Topic " + topicName + " is marked for deletion")));
        when(createFail.all()).thenReturn(createFailFuture);

        CreateTopicsResult createSuccess = mock(CreateTopicsResult.class);
        when(createSuccess.all()).thenReturn(KafkaFuture.completedFuture(null));

        when(adminClient.createTopics(anyCollection()))
                .thenReturn(createFail)
                .thenReturn(createSuccess);

        service.recreateTopic(clusterId, topicName);

        verify(adminClient, times(2)).createTopics(anyCollection());
        verify(auditService).record(eq("DATA_SERVICES"), eq("TOPIC_RECREATE_COMPLETED"), eq("TOPIC"), eq(topicName),
                eq(clusterId), eq("SUCCESS"), isNull(), isNull(), isNull(), any());
    }

    @Test
    void recreateTopic_handlesConcurrentTopicExistsGracefully() throws Exception {
        Node n1 = new Node(1, "host1", 9092);
        TopicPartitionInfo p0 = new TopicPartitionInfo(0, n1, List.of(n1), List.of(n1));
        setupTopicMetadata(false, List.of(p0), Map.of(), List.of(n1));

        DeleteTopicsResult deleteTopicsResult = mock(DeleteTopicsResult.class);
        when(deleteTopicsResult.all()).thenReturn(KafkaFuture.completedFuture(null));
        when(adminClient.deleteTopics(List.of(topicName))).thenReturn(deleteTopicsResult);

        doNothing().when(service).waitForTopicPresence(adminClient, topicName, false, 20_000);
        doNothing().when(service).waitForTopicPresence(adminClient, topicName, true, 15_000);
        doReturn(TopicPresence.ABSENT).when(service).checkTopicPresence(adminClient, topicName);

        CreateTopicsResult createFail = mock(CreateTopicsResult.class);
        KafkaFuture<Void> createFailFuture = mock(KafkaFuture.class);
        when(createFailFuture.get(anyLong(), any())).thenThrow(new ExecutionException(
                new TopicExistsException("Topic already exists")));
        when(createFail.all()).thenReturn(createFailFuture);
        when(adminClient.createTopics(anyCollection())).thenReturn(createFail);

        service.recreateTopic(clusterId, topicName);

        verify(auditService).record(eq("DATA_SERVICES"), eq("TOPIC_RECREATE_COMPLETED"), eq("TOPIC"), eq(topicName),
                eq(clusterId), eq("SUCCESS"), isNull(), isNull(), isNull(), any());
    }

    @Test
    void recreateTopic_handlesCreationFailureAndVerifiesAbsentPresence() throws Exception {
        Node n1 = new Node(1, "host1", 9092);
        TopicPartitionInfo p0 = new TopicPartitionInfo(0, n1, List.of(n1), List.of(n1));
        setupTopicMetadata(false, List.of(p0), Map.of(), List.of(n1));

        DeleteTopicsResult deleteTopicsResult = mock(DeleteTopicsResult.class);
        when(deleteTopicsResult.all()).thenReturn(KafkaFuture.completedFuture(null));
        when(adminClient.deleteTopics(List.of(topicName))).thenReturn(deleteTopicsResult);

        doNothing().when(service).waitForTopicPresence(adminClient, topicName, false, 20_000);
        doReturn(TopicPresence.ABSENT).when(service).checkTopicPresence(adminClient, topicName);

        CreateTopicsResult createFail = mock(CreateTopicsResult.class);
        KafkaFuture<Void> createFailFuture = mock(KafkaFuture.class);
        when(createFailFuture.get(anyLong(), any())).thenThrow(new ExecutionException(
                new org.apache.kafka.common.errors.PolicyViolationException("Quota exceeded")));
        when(createFail.all()).thenReturn(createFailFuture);
        when(adminClient.createTopics(anyCollection())).thenReturn(createFail);

        assertThatThrownBy(() -> service.recreateTopic(clusterId, topicName))
                .isInstanceOf(TopicRecreationException.class)
                .satisfies(ex -> {
                    TopicRecreationException tre = (TopicRecreationException) ex;
                    assertThat(tre.getStage()).isEqualTo("RECREATION_FAILED");
                    assertThat(tre.getPresence()).isEqualTo(TopicPresence.ABSENT);
                });

        verify(auditService).record(eq("DATA_SERVICES"), eq("TOPIC_RECREATE_FAILED"), eq("TOPIC"), eq(topicName),
                eq(clusterId), eq("FAILED"), isNull(), isNull(), isNull(), any());
    }

    @Test
    void recreateTopic_handlesWaitTopicTimeoutWithPresenceCheck() throws Exception {
        Node n1 = new Node(1, "host1", 9092);
        TopicPartitionInfo p0 = new TopicPartitionInfo(0, n1, List.of(n1), List.of(n1));
        setupTopicMetadata(false, List.of(p0), Map.of(), List.of(n1));

        DeleteTopicsResult deleteTopicsResult = mock(DeleteTopicsResult.class);
        when(deleteTopicsResult.all()).thenReturn(KafkaFuture.completedFuture(null));
        when(adminClient.deleteTopics(List.of(topicName))).thenReturn(deleteTopicsResult);

        doNothing().when(service).waitForTopicPresence(adminClient, topicName, false, 20_000);
        doThrow(new TimeoutException("Timed out waiting for presence"))
                .when(service).waitForTopicPresence(adminClient, topicName, true, 15_000);
        doReturn(TopicPresence.ABSENT).when(service).checkTopicPresence(adminClient, topicName);

        CreateTopicsResult createSuccess = mock(CreateTopicsResult.class);
        when(createSuccess.all()).thenReturn(KafkaFuture.completedFuture(null));
        when(adminClient.createTopics(anyCollection())).thenReturn(createSuccess);

        assertThatThrownBy(() -> service.recreateTopic(clusterId, topicName))
                .isInstanceOf(TopicRecreationException.class)
                .satisfies(ex -> {
                    TopicRecreationException tre = (TopicRecreationException) ex;
                    assertThat(tre.getStage()).isEqualTo("METADATA_REFRESH_TIMEOUT");
                    assertThat(tre.getPresence()).isEqualTo(TopicPresence.PRESENT);
                });
    }

    @Test
    void TopicsController_invalidatesCacheOnRecreationFailure() {
        KafkaAdminService kafkaAdmin = mock(KafkaAdminService.class);
        TopicOperationsService topicOps = mock(TopicOperationsService.class);
        PartitionCacheService partitionCache = mock(PartitionCacheService.class);
        ClusterRepository clusters = mock(ClusterRepository.class);
        AuditService audit = mock(AuditService.class);
        RoleAuthenticationUtil roles = mock(RoleAuthenticationUtil.class);
        ActivityAlertService activities = mock(ActivityAlertService.class);

        when(roles.canAccess(any(), eq(RoleAuthenticationUtil.TOPIC_MUTATION))).thenReturn(true);
        doThrow(new TopicRecreationException(UUID.randomUUID(), "RECREATION_FAILED", TopicPresence.ABSENT, "Topic creation failed"))
                .when(topicOps).recreateTopic(clusterId, topicName);

        TopicsController controller = new TopicsController(
                kafkaAdmin, topicOps, partitionCache, clusters, audit, roles, activities);

        assertThatThrownBy(() -> controller.recreateTopic("token", clusterId, topicName))
                .isInstanceOf(TopicRecreationException.class);

        verify(partitionCache).invalidateMetadata(clusterId);
    }

    @Test
    void ApiExceptionHandler_mapsTopicRecreationExceptionToCorrectHttpStatus() {
        ApiExceptionHandler handler = new ApiExceptionHandler();

        TopicRecreationException absentEx = new TopicRecreationException(
                UUID.randomUUID(), "RECREATION_FAILED", TopicPresence.ABSENT, "Topic deleted and creation failed");
        ResponseEntity<ApiError> absentResp = handler.handleTopicRecreation(absentEx);
        assertThat(absentResp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(absentResp.getBody().getErrorCode()).isEqualTo("TOPIC_RECREATION_FAILED");
        assertThat(absentResp.getBody().getMessage()).contains("ABSENT");

        TopicRecreationException uncertainEx = new TopicRecreationException(
                UUID.randomUUID(), "WAIT_DELETION_FAILED", TopicPresence.UNCERTAIN, "Outcome uncertain");
        ResponseEntity<ApiError> uncertainResp = handler.handleTopicRecreation(uncertainEx);
        assertThat(uncertainResp.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(uncertainResp.getBody().getMessage()).contains("UNCERTAIN");

        TopicRecreationException unknownEx = new TopicRecreationException(
                UUID.randomUUID(), "RECREATION_FAILED", TopicPresence.UNKNOWN, "Kafka connection failed");
        ResponseEntity<ApiError> unknownResp = handler.handleTopicRecreation(unknownEx);
        assertThat(unknownResp.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(unknownResp.getBody().getMessage()).contains("UNKNOWN");

        TopicRecreationException presentEx = new TopicRecreationException(
                UUID.randomUUID(), "DELETION_FAILED", TopicPresence.PRESENT, "Topic still exists");
        ResponseEntity<ApiError> presentResp = handler.handleTopicRecreation(presentEx);
        assertThat(presentResp.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(presentResp.getBody().getMessage()).contains("PRESENT");
    }
}
