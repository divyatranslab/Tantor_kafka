package io.translab.tantor.server.service;

import io.translab.tantor.server.audit.AuditService;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.ConsumerGroupState;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.errors.CoordinatorNotAvailableException;
import org.apache.kafka.common.errors.GroupAuthorizationException;
import org.apache.kafka.common.errors.TopicAuthorizationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TopicOperationsServiceConsumerGroupOffsetsTest {

    @Mock private KafkaAdminService kafkaAdminService;
    @Mock private AuditService auditService;
    @Mock private AdminClient adminClient;
    @Mock private ListConsumerGroupsResult listConsumerGroupsResult;
    @Mock private DescribeConsumerGroupsResult describeConsumerGroupsResult;
    @Mock private ListConsumerGroupOffsetsResult listConsumerGroupOffsetsResult;
    @Mock private DescribeTopicsResult describeTopicsResult;
    @Mock private ListOffsetsResult listOffsetsResult;

    private TopicOperationsService service;
    private final UUID clusterId = UUID.randomUUID();
    private final String topicName = "orders-topic";

    @BeforeEach
    void setUp() {
        service = new TopicOperationsService(kafkaAdminService, auditService);
        ReflectionTestUtils.setField(service, "consumerGroupOperationTimeoutMillis", 10_000L);
        when(kafkaAdminService.getAdminClient(clusterId)).thenReturn(adminClient);
    }

    @Test
    void allConsumerGroupOffsetLookupsSucceedReturnsExpectedResults() throws Exception {
        setupListGroups("group-a", "group-b");
        setupDescribeGroups(
                groupDescription("group-a", "host-1", 9092),
                groupDescription("group-b", "host-2", 9092)
        );

        TopicPartition p0 = new TopicPartition(topicName, 0);
        when(adminClient.listConsumerGroupOffsets(anyMap(), any(ListConsumerGroupOffsetsOptions.class)))
                .thenReturn(listConsumerGroupOffsetsResult);
        when(listConsumerGroupOffsetsResult.partitionsToOffsetAndMetadata("group-a"))
                .thenReturn(KafkaFuture.completedFuture(Map.of(p0, new OffsetAndMetadata(10L))));
        when(listConsumerGroupOffsetsResult.partitionsToOffsetAndMetadata("group-b"))
                .thenReturn(KafkaFuture.completedFuture(Map.of(p0, new OffsetAndMetadata(20L))));

        setupTopicMetadata(p0, 25L);

        List<Map<String, Object>> consumers = service.getTopicConsumers(clusterId, topicName);

        assertThat(consumers).hasSize(2);
        assertThat(consumers)
                .extracting(m -> m.get("groupId"))
                .containsExactly("group-a", "group-b");
        assertThat(consumers)
                .extracting(m -> m.get("lag"))
                .containsExactly(15L, 5L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void offsetLookupFailsWithCoordinatorExceptionIdentifiesGroupAndPreservesCause() throws Exception {
        setupListGroups("payments-group");
        setupDescribeGroups(groupDescription("payments-group", "host-1", 9092));

        when(adminClient.listConsumerGroupOffsets(anyMap(), any(ListConsumerGroupOffsetsOptions.class)))
                .thenReturn(listConsumerGroupOffsetsResult);
        KafkaFuture<Map<TopicPartition, OffsetAndMetadata>> failingFuture = mock(KafkaFuture.class);
        when(failingFuture.get(any(Long.class), any(TimeUnit.class)))
                .thenThrow(new ExecutionException(new CoordinatorNotAvailableException("The coordinator is not available")));
        when(listConsumerGroupOffsetsResult.partitionsToOffsetAndMetadata("payments-group"))
                .thenReturn(failingFuture);

        assertThatThrownBy(() -> service.getTopicConsumers(clusterId, topicName))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("payments-group")
                .hasMessageContaining("The coordinator is not available")
                .hasRootCauseInstanceOf(CoordinatorNotAvailableException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void offsetLookupFailsWithAuthorizationExceptionPropagatesAndIdentifiesGroup() throws Exception {
        setupListGroups("billing-group");
        setupDescribeGroups(groupDescription("billing-group", "host-1", 9092));

        when(adminClient.listConsumerGroupOffsets(anyMap(), any(ListConsumerGroupOffsetsOptions.class)))
                .thenReturn(listConsumerGroupOffsetsResult);
        KafkaFuture<Map<TopicPartition, OffsetAndMetadata>> failingFuture = mock(KafkaFuture.class);
        when(failingFuture.get(any(Long.class), any(TimeUnit.class)))
                .thenThrow(new ExecutionException(new GroupAuthorizationException("Not authorized to access group: billing-group")));
        when(listConsumerGroupOffsetsResult.partitionsToOffsetAndMetadata("billing-group"))
                .thenReturn(failingFuture);

        assertThatThrownBy(() -> service.getTopicConsumers(clusterId, topicName))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("billing-group")
                .hasMessageContaining("Not authorized to access group")
                .hasRootCauseInstanceOf(GroupAuthorizationException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void offsetLookupTimesOutFailsClearlyWithoutReturningIncompleteResults() throws Exception {
        setupListGroups("slow-group");
        setupDescribeGroups(groupDescription("slow-group", "host-1", 9092));

        when(adminClient.listConsumerGroupOffsets(anyMap(), any(ListConsumerGroupOffsetsOptions.class)))
                .thenReturn(listConsumerGroupOffsetsResult);
        KafkaFuture<Map<TopicPartition, OffsetAndMetadata>> timingOutFuture = mock(KafkaFuture.class);
        when(timingOutFuture.get(any(Long.class), any(TimeUnit.class)))
                .thenThrow(new TimeoutException("Timed out waiting for group offset response"));
        when(listConsumerGroupOffsetsResult.partitionsToOffsetAndMetadata("slow-group"))
                .thenReturn(timingOutFuture);

        assertThatThrownBy(() -> service.getTopicConsumers(clusterId, topicName))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("slow-group")
                .hasRootCauseInstanceOf(TimeoutException.class);
    }

    @Test
    void kafkaReturnsNoFutureForRequestedGroupDetectsIncompleteResults() {
        setupListGroups("missing-future-group");
        setupDescribeGroups(groupDescription("missing-future-group", "host-1", 9092));

        when(adminClient.listConsumerGroupOffsets(anyMap(), any(ListConsumerGroupOffsetsOptions.class)))
                .thenReturn(listConsumerGroupOffsetsResult);
        when(listConsumerGroupOffsetsResult.partitionsToOffsetAndMetadata("missing-future-group"))
                .thenReturn(null);

        assertThatThrownBy(() -> service.getTopicConsumers(clusterId, topicName))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("missing-future-group")
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("Kafka omitted committed offsets for consumer group missing-future-group");
    }

    @Test
    @SuppressWarnings("unchecked")
    void multipleGroupsProcessedSuccessfulGroupDoesNotHideFailedGroup() throws Exception {
        setupListGroups("healthy-group", "failed-group");
        setupDescribeGroups(
                groupDescription("healthy-group", "host-1", 9092),
                groupDescription("failed-group", "host-2", 9092)
        );

        TopicPartition p0 = new TopicPartition(topicName, 0);
        when(adminClient.listConsumerGroupOffsets(anyMap(), any(ListConsumerGroupOffsetsOptions.class)))
                .thenReturn(listConsumerGroupOffsetsResult);
        when(listConsumerGroupOffsetsResult.partitionsToOffsetAndMetadata("healthy-group"))
                .thenReturn(KafkaFuture.completedFuture(Map.of(p0, new OffsetAndMetadata(10L))));

        KafkaFuture<Map<TopicPartition, OffsetAndMetadata>> failingFuture = mock(KafkaFuture.class);
        when(failingFuture.get(any(Long.class), any(TimeUnit.class)))
                .thenThrow(new ExecutionException(new CoordinatorNotAvailableException("Coordinator disconnected")));
        when(listConsumerGroupOffsetsResult.partitionsToOffsetAndMetadata("failed-group"))
                .thenReturn(failingFuture);

        assertThatThrownBy(() -> service.getTopicConsumers(clusterId, topicName))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("failed-group")
                .hasMessageContaining("Coordinator disconnected")
                .hasRootCauseInstanceOf(CoordinatorNotAvailableException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void interruptedLookupRestoresInterruptFlag() throws Exception {
        setupListGroups("interrupted-group");
        setupDescribeGroups(groupDescription("interrupted-group", "host-1", 9092));

        when(adminClient.listConsumerGroupOffsets(anyMap(), any(ListConsumerGroupOffsetsOptions.class)))
                .thenReturn(listConsumerGroupOffsetsResult);
        KafkaFuture<Map<TopicPartition, OffsetAndMetadata>> interruptedFuture = mock(KafkaFuture.class);
        when(interruptedFuture.get(any(Long.class), any(TimeUnit.class)))
                .thenThrow(new InterruptedException("Interrupted during group offset fetch"));
        when(listConsumerGroupOffsetsResult.partitionsToOffsetAndMetadata("interrupted-group"))
                .thenReturn(interruptedFuture);

        try {
            assertThatThrownBy(() -> service.getTopicConsumers(clusterId, topicName))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Interrupted while loading topic consumers")
                    .hasRootCauseInstanceOf(InterruptedException.class);

            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void originalKafkaExceptionPreservedAsCause() throws Exception {
        setupListGroups("audit-group");
        setupDescribeGroups(groupDescription("audit-group", "host-1", 9092));

        TopicAuthorizationException authException = new TopicAuthorizationException(Set.of(topicName));
        when(adminClient.listConsumerGroupOffsets(anyMap(), any(ListConsumerGroupOffsetsOptions.class)))
                .thenReturn(listConsumerGroupOffsetsResult);
        KafkaFuture<Map<TopicPartition, OffsetAndMetadata>> failingFuture = mock(KafkaFuture.class);
        when(failingFuture.get(any(Long.class), any(TimeUnit.class)))
                .thenThrow(new ExecutionException(authException));
        when(listConsumerGroupOffsetsResult.partitionsToOffsetAndMetadata("audit-group"))
                .thenReturn(failingFuture);

        assertThatThrownBy(() -> service.getTopicConsumers(clusterId, topicName))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("audit-group")
                .hasRootCause(authException);
    }

    private void setupListGroups(String... groupIds) {
        List<ConsumerGroupListing> listings = java.util.Arrays.stream(groupIds)
                .map(id -> new ConsumerGroupListing(id, false))
                .toList();
        when(adminClient.listConsumerGroups(any(ListConsumerGroupsOptions.class)))
                .thenReturn(listConsumerGroupsResult);
        when(listConsumerGroupsResult.all())
                .thenReturn(KafkaFuture.completedFuture(listings));
    }

    private void setupDescribeGroups(ConsumerGroupDescription... descriptions) {
        Map<String, ConsumerGroupDescription> descMap = new java.util.LinkedHashMap<>();
        for (ConsumerGroupDescription d : descriptions) {
            descMap.put(d.groupId(), d);
        }
        when(adminClient.describeConsumerGroups(anyCollection(), any(DescribeConsumerGroupsOptions.class)))
                .thenReturn(describeConsumerGroupsResult);
        when(describeConsumerGroupsResult.all())
                .thenReturn(KafkaFuture.completedFuture(descMap));
    }

    private ConsumerGroupDescription groupDescription(String groupId, String host, int port) {
        return new ConsumerGroupDescription(
                groupId,
                false,
                List.of(),
                "range",
                ConsumerGroupState.STABLE,
                new Node(1, host, port)
        );
    }

    private void setupTopicMetadata(TopicPartition partition, long latestOffset) {
        TopicPartitionInfo pInfo = new TopicPartitionInfo(
                partition.partition(),
                new Node(1, "host", 9092),
                List.of(new Node(1, "host", 9092)),
                List.of(new Node(1, "host", 9092))
        );
        TopicDescription topicDesc = new TopicDescription(
                partition.topic(),
                false,
                List.of(pInfo)
        );

        when(adminClient.describeTopics(anyCollection(), any(DescribeTopicsOptions.class)))
                .thenReturn(describeTopicsResult);
        when(describeTopicsResult.allTopicNames())
                .thenReturn(KafkaFuture.completedFuture(Map.of(partition.topic(), topicDesc)));

        ListOffsetsResult.ListOffsetsResultInfo offsetInfo = mock(ListOffsetsResult.ListOffsetsResultInfo.class);
        when(offsetInfo.offset()).thenReturn(latestOffset);

        when(adminClient.listOffsets(anyMap(), any(ListOffsetsOptions.class)))
                .thenReturn(listOffsetsResult);
        when(listOffsetsResult.all())
                .thenReturn(KafkaFuture.completedFuture(Map.of(partition, offsetInfo)));
    }
}
