package io.translab.tantor.server.service;

import io.translab.tantor.server.audit.AuditService;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TopicOperationsServiceDeadlineTest {
    @Test
    void rejectsSameSizedButIncompleteLatestOffsetResult() {
        KafkaAdminService kafka = mock(KafkaAdminService.class);
        AdminClient admin = mock(AdminClient.class);
        UUID clusterId = UUID.randomUUID();
        when(kafka.getAdminClient(clusterId)).thenReturn(admin);

        ConsumerGroupListing group = mock(ConsumerGroupListing.class);
        when(group.groupId()).thenReturn("group-1");
        ListConsumerGroupsResult groups = mock(ListConsumerGroupsResult.class);
        when(groups.all()).thenReturn(KafkaFuture.completedFuture(List.<ConsumerGroupListing>of(group)));
        when(admin.listConsumerGroups(any(ListConsumerGroupsOptions.class))).thenReturn(groups);

        ConsumerGroupDescription groupDescription = new ConsumerGroupDescription("group-1", false, List.of(),
                "range", org.apache.kafka.common.ConsumerGroupState.EMPTY, new Node(1, "localhost", 9092));
        DescribeConsumerGroupsResult descriptions = mock(DescribeConsumerGroupsResult.class);
        when(descriptions.all()).thenReturn(KafkaFuture.completedFuture(Map.of("group-1", groupDescription)));
        when(admin.describeConsumerGroups(anyCollection(), any(DescribeConsumerGroupsOptions.class))).thenReturn(descriptions);

        TopicPartition p0 = new TopicPartition("topic", 0);
        TopicPartition p1 = new TopicPartition("topic", 1);
        ListConsumerGroupOffsetsResult groupOffsets = mock(ListConsumerGroupOffsetsResult.class);
        when(groupOffsets.all()).thenReturn(KafkaFuture.completedFuture(Map.of("group-1", Map.of(p0, new OffsetAndMetadata(5L)))));
        when(admin.listConsumerGroupOffsets(anyMap(), any(ListConsumerGroupOffsetsOptions.class))).thenReturn(groupOffsets);

        TopicPartitionInfo info0 = mock(TopicPartitionInfo.class);
        TopicPartitionInfo info1 = mock(TopicPartitionInfo.class);
        when(info0.partition()).thenReturn(0);
        when(info1.partition()).thenReturn(1);
        TopicDescription topic = mock(TopicDescription.class);
        when(topic.name()).thenReturn("topic");
        when(topic.partitions()).thenReturn(List.of(info0, info1));
        DescribeTopicsResult topics = mock(DescribeTopicsResult.class);
        when(topics.allTopicNames()).thenReturn(KafkaFuture.completedFuture(Map.of("topic", topic)));
        when(admin.describeTopics(anyCollection(), any(DescribeTopicsOptions.class))).thenReturn(topics);

        TopicPartition wrong0 = new TopicPartition("other", 0);
        TopicPartition wrong1 = new TopicPartition("other", 1);
        ListOffsetsResult.ListOffsetsResultInfo offset = mock(ListOffsetsResult.ListOffsetsResultInfo.class);
        when(offset.offset()).thenReturn(10L);
        ListOffsetsResult offsets = mock(ListOffsetsResult.class);
        when(offsets.all()).thenReturn(KafkaFuture.completedFuture(Map.of(wrong0, offset, wrong1, offset)));
        when(admin.listOffsets(anyMap(), any(ListOffsetsOptions.class))).thenReturn(offsets);

        TopicOperationsService service = new TopicOperationsService(kafka, mock(AuditService.class));
        ReflectionTestUtils.setField(service, "consumerGroupOperationTimeoutMillis", 30_000L);

        assertThatThrownBy(() -> service.getTopicConsumers(clusterId, "topic"))
                .isInstanceOf(RuntimeException.class)
                .hasRootCauseMessage("Kafka returned incomplete latest offsets for topic topic");
    }
}
