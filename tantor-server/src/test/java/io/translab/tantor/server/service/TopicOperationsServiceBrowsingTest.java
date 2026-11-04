package io.translab.tantor.server.service;

import io.translab.tantor.server.audit.AuditService;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.InterruptException;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TopicOperationsServiceBrowsingTest {

    private KafkaAdminService kafkaAdminService;
    private AuditService auditService;
    private Consumer<byte[], byte[]> mockConsumer;
    private TestableTopicOperationsService service;
    private UUID clusterId;
    private final String topic = "test-topic";

    static class TestableTopicOperationsService extends TopicOperationsService {
        private final Consumer<byte[], byte[]> consumerToReturn;

        TestableTopicOperationsService(
                KafkaAdminService kafkaAdminService,
                AuditService auditService,
                Consumer<byte[], byte[]> consumerToReturn) {
            super(kafkaAdminService, auditService);
            this.consumerToReturn = consumerToReturn;
        }

        @Override
        Consumer<byte[], byte[]> createConsumer(Properties properties) {
            return consumerToReturn;
        }
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        kafkaAdminService = mock(KafkaAdminService.class);
        auditService = mock(AuditService.class);
        mockConsumer = mock(Consumer.class);
        clusterId = UUID.randomUUID();

        when(kafkaAdminService.getKafkaClientProperties(clusterId)).thenReturn(new Properties());

        service = new TestableTopicOperationsService(kafkaAdminService, auditService, mockConsumer);
        service.setMaxConcurrentRequests(8);
        service.setBrowserTimeoutMillis(5000L);
    }

    private ConsumerRecord<byte[], byte[]> record(int partition, long offset, long timestamp, String key, String value) {
        byte[] k = key == null ? null : key.getBytes(StandardCharsets.UTF_8);
        byte[] v = value == null ? null : value.getBytes(StandardCharsets.UTF_8);
        return new ConsumerRecord<>(topic, partition, offset, timestamp, TimestampType.CREATE_TIME,
                0, k == null ? 0 : k.length, v == null ? 0 : v.length, k, v);
    }

    @SafeVarargs
    private ConsumerRecords<byte[], byte[]> recordsOf(ConsumerRecord<byte[], byte[]>... records) {
        Map<TopicPartition, List<ConsumerRecord<byte[], byte[]>>> map = new LinkedHashMap<>();
        for (var r : records) {
            map.computeIfAbsent(new TopicPartition(r.topic(), r.partition()), ignored -> new ArrayList<>()).add(r);
        }
        return new ConsumerRecords<>(map);
    }

    private void setupPartitions(int partitionCount, long messagesPerPartition) {
        List<PartitionInfo> infos = new ArrayList<>();
        Map<TopicPartition, Long> beginnings = new HashMap<>();
        Map<TopicPartition, Long> ends = new HashMap<>();
        for (int i = 0; i < partitionCount; i++) {
            infos.add(new PartitionInfo(topic, i, null, null, null));
            TopicPartition tp = new TopicPartition(topic, i);
            beginnings.put(tp, 0L);
            ends.put(tp, messagesPerPartition);
        }
        when(mockConsumer.partitionsFor(eq(topic), any(Duration.class))).thenReturn(infos);
        when(mockConsumer.beginningOffsets(anyCollection())).thenReturn(beginnings);
        when(mockConsumer.endOffsets(anyCollection())).thenReturn(ends);
    }

    // 1. One partition with a small requested limit
    @Test
    @DisplayName("1. One partition with small requested limit")
    void onePartitionSmallLimit() {
        setupPartitions(1, 50);
        TopicPartition tp0 = new TopicPartition(topic, 0);

        List<ConsumerRecord<byte[], byte[]>> recs = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            recs.add(record(0, i, 1000L + i, "k" + i, "v" + i));
        }

        when(mockConsumer.poll(any(Duration.class)))
                .thenReturn(recordsOf(recs.toArray(new ConsumerRecord[0])))
                .thenReturn(ConsumerRecords.empty());
        when(mockConsumer.position(tp0)).thenReturn(50L);

        var result = service.readRecords(clusterId, topic, null, "newest", 5, null);
        assertThat(result.records()).hasSize(5);
        assertThat(result.records().get(0).offset()).isEqualTo(49);
        assertThat(result.records().get(4).offset()).isEqualTo(45);
    }

    // 2. Many partitions, such as 100 partitions
    @Test
    @DisplayName("2. Many partitions (100 partitions)")
    void manyPartitions100() {
        setupPartitions(100, 10);

        List<ConsumerRecord<byte[], byte[]>> recs = new ArrayList<>();
        for (int p = 0; p < 100; p++) {
            for (int off = 0; off < 10; off++) {
                recs.add(record(p, off, p * 1000L + off, "k", "v"));
            }
            TopicPartition tp = new TopicPartition(topic, p);
            when(mockConsumer.position(tp)).thenReturn(10L);
        }

        when(mockConsumer.poll(any(Duration.class)))
                .thenReturn(recordsOf(recs.toArray(new ConsumerRecord[0])))
                .thenReturn(ConsumerRecords.empty());

        var result = service.readRecords(clusterId, topic, null, "newest", 10, null);
        assertThat(result.records()).hasSize(10);
    }

    // 3. Verify the global scan budget does not multiply with partition count
    @Test
    @DisplayName("3. Global scan budget does not multiply with partition count")
    void scanBudgetDoesNotMultiplyWithPartitionCount() {
        setupPartitions(100, 200);
        service.setMaxScanRecords(50);

        List<ConsumerRecord<byte[], byte[]>> batch = new ArrayList<>();
        for (int p = 0; p < 100; p++) {
            batch.add(record(p, 0, 1000L, "k", "v"));
        }

        when(mockConsumer.poll(any(Duration.class)))
                .thenReturn(recordsOf(batch.toArray(new ConsumerRecord[0])));

        var result = service.readRecords(clusterId, topic, null, "newest", 10, null);
        assertThat(result.scannedRecords()).isEqualTo(50);
        assertThat(result.truncated()).isTrue();
        assertThat(result.truncationReason()).isEqualTo("Global scan budget reached");
    }

    // 4. Verify returned result never exceeds requested limit
    @Test
    @DisplayName("4. Returned result never exceeds requested limit")
    void returnedResultNeverExceedsRequestedLimit() {
        setupPartitions(1, 100);
        TopicPartition tp0 = new TopicPartition(topic, 0);

        List<ConsumerRecord<byte[], byte[]>> recs = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            recs.add(record(0, i, 1000L + i, "k", "v"));
        }

        when(mockConsumer.poll(any(Duration.class)))
                .thenReturn(recordsOf(recs.toArray(new ConsumerRecord[0])))
                .thenReturn(ConsumerRecords.empty());
        when(mockConsumer.position(tp0)).thenReturn(50L);

        var result = service.readRecords(clusterId, topic, null, "newest", 7, null);
        assertThat(result.records()).hasSize(7);
    }

    // 5. Verify both newest and oldest ordering
    @Test
    @DisplayName("5. Both newest and oldest ordering")
    void newestAndOldestOrdering() {
        setupPartitions(1, 5);
        TopicPartition tp0 = new TopicPartition(topic, 0);

        var r1 = record(0, 0, 100L, "k", "v1");
        var r2 = record(0, 1, 200L, "k", "v2");
        var r3 = record(0, 2, 300L, "k", "v3");
        var r4 = record(0, 3, 400L, "k", "v4");
        var r5 = record(0, 4, 500L, "k", "v5");

        when(mockConsumer.poll(any(Duration.class)))
                .thenReturn(recordsOf(r1, r2, r3, r4, r5))
                .thenReturn(ConsumerRecords.empty());
        when(mockConsumer.position(tp0)).thenReturn(5L);

        var newest = service.readRecords(clusterId, topic, null, "newest", 3, null);
        assertThat(newest.records()).extracting(ConsumerRecord::timestamp).containsExactly(500L, 400L, 300L);

        when(mockConsumer.poll(any(Duration.class)))
                .thenReturn(recordsOf(r1, r2, r3, r4, r5))
                .thenReturn(ConsumerRecords.empty());

        var oldest = service.readRecords(clusterId, topic, null, "oldest", 3, null);
        assertThat(oldest.records()).extracting(ConsumerRecord::timestamp).containsExactly(100L, 200L, 300L);
    }

    // 6. Verify deterministic timestamp/partition/offset tie-breaking
    @Test
    @DisplayName("6. Deterministic tie-breaking by timestamp, partition, offset")
    void deterministicTieBreaking() {
        setupPartitions(3, 5);
        for (int p = 0; p < 3; p++) {
            when(mockConsumer.position(new TopicPartition(topic, p))).thenReturn(5L);
        }

        var rA = record(0, 1, 1000L, "k", "A");
        var rB = record(1, 1, 1000L, "k", "B");
        var rC = record(1, 2, 1000L, "k", "C");

        when(mockConsumer.poll(any(Duration.class)))
                .thenReturn(recordsOf(rA, rB, rC))
                .thenReturn(ConsumerRecords.empty());

        var newest = service.readRecords(clusterId, topic, null, "newest", 3, null);
        assertThat(newest.records()).containsExactly(rC, rB, rA);

        when(mockConsumer.poll(any(Duration.class)))
                .thenReturn(recordsOf(rA, rB, rC))
                .thenReturn(ConsumerRecords.empty());

        var oldest = service.readRecords(clusterId, topic, null, "oldest", 3, null);
        assertThat(oldest.records()).containsExactly(rA, rB, rC);
    }

    // 7. Verify selected-partition filtering
    @Test
    @DisplayName("7. Selected-partition filtering")
    void selectedPartitionFiltering() {
        setupPartitions(3, 10);
        TopicPartition tp1 = new TopicPartition(topic, 1);
        when(mockConsumer.position(tp1)).thenReturn(10L);

        when(mockConsumer.poll(any(Duration.class)))
                .thenReturn(recordsOf(record(1, 0, 100L, "k", "v")))
                .thenReturn(ConsumerRecords.empty());

        var result = service.readRecords(clusterId, topic, List.of(1), "newest", 10, null);
        verify(mockConsumer).assign(List.of(tp1));
        assertThat(result.records()).hasSize(1);
        assertThat(result.records().get(0).partition()).isEqualTo(1);
    }

    // 8. Multiple large records reaching the byte budget
    @Test
    @DisplayName("8. Multiple large records reaching byte budget")
    void multipleLargeRecordsReachByteBudget() {
        setupPartitions(1, 10);
        service.setMaxRetainedBytes(1000L);

        String payload400 = "x".repeat(400);
        var r1 = record(0, 0, 100L, "k", payload400); // ~530 bytes
        var r2 = record(0, 1, 200L, "k", payload400); // 2nd record would be ~1060 bytes > 1000

        when(mockConsumer.poll(any(Duration.class)))
                .thenReturn(recordsOf(r1, r2));

        var result = service.readRecords(clusterId, topic, null, "newest", 10, null);
        assertThat(result.truncated()).isTrue();
        assertThat(result.truncationReason()).isEqualTo("Retained byte budget reached");
        assertThat(result.retainedBytes()).isLessThanOrEqualTo(1000L);
    }

    // 9. A single record larger than the configured budget
    @Test
    @DisplayName("9. Single record larger than configured budget")
    void singleRecordLargerThanBudget() {
        setupPartitions(1, 10);
        service.setMaxRetainedBytes(500L);

        String payload600 = "x".repeat(600);
        var r1 = record(0, 0, 100L, "k", payload600);

        when(mockConsumer.poll(any(Duration.class))).thenReturn(recordsOf(r1));

        var result = service.readRecords(clusterId, topic, null, "newest", 10, null);
        assertThat(result.records()).isEmpty();
        assertThat(result.truncated()).isTrue();
        assertThat(result.truncationReason()).isEqualTo("Retained byte budget reached");
        assertThat(result.retainedBytes()).isEqualTo(0L);
    }

    // 10. Verify retained bytes never exceed configured limit
    @Test
    @DisplayName("10. Retained bytes never exceed configured limit")
    void retainedBytesNeverExceedLimit() {
        setupPartitions(1, 20);
        long budget = 2000L;
        service.setMaxRetainedBytes(budget);

        List<ConsumerRecord<byte[], byte[]>> recs = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            recs.add(record(0, i, 1000L + i, "k", "y".repeat(300)));
        }

        when(mockConsumer.poll(any(Duration.class))).thenReturn(recordsOf(recs.toArray(new ConsumerRecord[0])));

        var result = service.readRecords(clusterId, topic, null, "newest", 10, null);
        assertThat(result.retainedBytes()).isLessThanOrEqualTo(budget);
    }

    // 11. Verify consumer closes when memory limit reached
    @Test
    @DisplayName("11. Consumer closes when memory limit reached")
    void consumerClosesWhenMemoryLimitReached() {
        setupPartitions(1, 10);
        service.setMaxRetainedBytes(200L);

        when(mockConsumer.poll(any(Duration.class)))
                .thenReturn(recordsOf(record(0, 0, 100L, "k", "x".repeat(300))));

        service.readRecords(clusterId, topic, null, "newest", 10, null);
        verify(mockConsumer).close();
    }

    // 12. Search with many nonmatching records
    @Test
    @DisplayName("12. Search with many nonmatching records")
    void searchWithManyNonmatchingRecords() {
        setupPartitions(1, 10);
        TopicPartition tp0 = new TopicPartition(topic, 0);
        when(mockConsumer.position(tp0)).thenReturn(10L);

        List<ConsumerRecord<byte[], byte[]>> recs = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            recs.add(record(0, i, 100L + i, "ignore", "not-matching"));
        }
        recs.add(record(0, 9, 200L, "target-key", "important-value"));

        when(mockConsumer.poll(any(Duration.class)))
                .thenReturn(recordsOf(recs.toArray(new ConsumerRecord[0])))
                .thenReturn(ConsumerRecords.empty());

        var result = service.readRecords(clusterId, topic, null, "newest", 10, "target-key");
        assertThat(result.records()).hasSize(1);
        assertThat(result.records().get(0).offset()).isEqualTo(9);
    }

    // 13. Search with only a few matching records
    @Test
    @DisplayName("13. Search with few matching records")
    void searchWithFewMatchingRecords() {
        setupPartitions(1, 10);
        TopicPartition tp0 = new TopicPartition(topic, 0);
        when(mockConsumer.position(tp0)).thenReturn(10L);

        var r1 = record(0, 0, 100L, "k1", "apple");
        var r2 = record(0, 1, 200L, "k2", "banana");
        var r3 = record(0, 2, 300L, "k3", "apple pie");

        when(mockConsumer.poll(any(Duration.class)))
                .thenReturn(recordsOf(r1, r2, r3))
                .thenReturn(ConsumerRecords.empty());

        var result = service.readRecords(clusterId, topic, null, "newest", 10, "apple");
        assertThat(result.records()).hasSize(2);
    }

    // 14. Verify nonmatching records still count against scan budget
    @Test
    @DisplayName("14. Nonmatching records count against scan budget")
    void nonmatchingRecordsCountAgainstScanBudget() {
        setupPartitions(1, 100);
        service.setMaxScanRecords(30);

        List<ConsumerRecord<byte[], byte[]>> recs = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            recs.add(record(0, i, 100L + i, "k", "unmatched"));
        }

        when(mockConsumer.poll(any(Duration.class))).thenReturn(recordsOf(recs.toArray(new ConsumerRecord[0])));

        var result = service.readRecords(clusterId, topic, null, "newest", 10, "needle");
        assertThat(result.scannedRecords()).isEqualTo(30);
        assertThat(result.records()).isEmpty();
        assertThat(result.truncated()).isTrue();
        assertThat(result.truncationReason()).isEqualTo("Global scan budget reached");
    }

    // 15. Deadline reached during polling
    @Test
    @DisplayName("15. Deadline reached before/during polling")
    void deadlineReachedDuringPolling() {
        setupPartitions(1, 100);
        AtomicLong time = new AtomicLong(0L);
        service.setNanoTimeSupplier(time::get);
        service.setBrowserTimeoutMillis(1000L);

        // Before first poll: time is 0. Inside while: time advanced to 2000 ms (past deadline 1000ms)
        when(mockConsumer.poll(any(Duration.class))).thenAnswer(inv -> {
            time.set(2_000_000_000L);
            return ConsumerRecords.empty();
        });

        var result = service.readRecords(clusterId, topic, null, "newest", 10, null);
        assertThat(result.truncated()).isTrue();
        assertThat(result.truncationReason()).isEqualTo("Execution deadline exceeded");
    }

    // 16. Deadline reached while processing poll batch
    @Test
    @DisplayName("16. Deadline reached while processing poll batch")
    void deadlineReachedWhileProcessingBatch() {
        setupPartitions(1, 100);
        AtomicLong time = new AtomicLong(0L);
        service.setNanoTimeSupplier(() -> time.addAndGet(500_000_000L)); // each check advances 500ms
        service.setBrowserTimeoutMillis(1000L); // deadline = 1000ms

        List<ConsumerRecord<byte[], byte[]>> batch = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            batch.add(record(0, i, 100L + i, "k", "v"));
        }

        when(mockConsumer.poll(any(Duration.class))).thenReturn(recordsOf(batch.toArray(new ConsumerRecord[0])));

        var result = service.readRecords(clusterId, topic, null, "newest", 10, null);
        assertThat(result.truncated()).isTrue();
        assertThat(result.truncationReason()).isEqualTo("Execution deadline exceeded");
        assertThat(result.records().size()).isLessThan(10);
    }

    // 17. Verify interruption is handled correctly
    @Test
    @DisplayName("17. Interruption is handled correctly")
    void interruptionHandledCorrectly() {
        setupPartitions(1, 10);
        when(mockConsumer.poll(any(Duration.class))).thenThrow(new InterruptException("Interrupted!"));

        assertThatThrownBy(() -> service.readRecords(clusterId, topic, null, "newest", 10, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Interrupted");

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        // Clear interrupt status for subsequent tests
        Thread.interrupted();
    }

    // 18. Verify consumer cleanup after timeout and failure
    @Test
    @DisplayName("18. Consumer cleanup after exception")
    void consumerCleanupAfterException() {
        setupPartitions(1, 10);
        when(mockConsumer.poll(any(Duration.class))).thenThrow(new IllegalStateException("Kafka failure"));

        assertThatThrownBy(() -> service.readRecords(clusterId, topic, null, "newest", 10, null))
                .isInstanceOf(IllegalStateException.class);

        verify(mockConsumer).close();
    }

    // 19. Complete message-browse result
    @Test
    @DisplayName("19. Complete message-browse result")
    void completeMessageBrowseResult() {
        setupPartitions(1, 5);
        TopicPartition tp0 = new TopicPartition(topic, 0);
        when(mockConsumer.position(tp0)).thenReturn(5L);

        List<ConsumerRecord<byte[], byte[]>> recs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            recs.add(record(0, i, 100L + i, "k", "v"));
        }

        when(mockConsumer.poll(any(Duration.class)))
                .thenReturn(recordsOf(recs.toArray(new ConsumerRecord[0])))
                .thenReturn(ConsumerRecords.empty());

        var response = service.getMessages(clusterId, topic, null, "newest", 10, null);
        assertThat(response.get("truncated")).isEqualTo(false);
        assertThat(response.get("truncationReason")).isNull();
        assertThat(response.get("count")).isEqualTo(5);
        assertThat((List<?>) response.get("messages")).hasSize(5);
    }

    // 20. Partial message-browse result
    @Test
    @DisplayName("20. Partial message-browse result")
    void partialMessageBrowseResult() {
        setupPartitions(1, 100);
        service.setMaxScanRecords(20);

        List<ConsumerRecord<byte[], byte[]>> recs = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            recs.add(record(0, i, 100L + i, "k", "v"));
        }

        when(mockConsumer.poll(any(Duration.class))).thenReturn(recordsOf(recs.toArray(new ConsumerRecord[0])));

        var response = service.getMessages(clusterId, topic, null, "newest", 10, null);
        assertThat(response.get("truncated")).isEqualTo(true);
        assertThat(response.get("truncationReason")).isEqualTo("Global scan budget reached");
        assertThat(response.get("scannedRecords")).isEqualTo(20L);
    }

    // 21. Complete statistics result
    @Test
    @DisplayName("21. Complete statistics result")
    void completeStatisticsResult() {
        setupPartitions(1, 5);
        TopicPartition tp0 = new TopicPartition(topic, 0);
        when(mockConsumer.position(tp0)).thenReturn(5L);

        List<ConsumerRecord<byte[], byte[]>> recs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            recs.add(record(0, i, 100L + i, "k" + i, "v" + i));
        }

        when(mockConsumer.poll(any(Duration.class)))
                .thenReturn(recordsOf(recs.toArray(new ConsumerRecord[0])))
                .thenReturn(ConsumerRecords.empty());

        var stats = service.analyzeTopic(clusterId, topic, 10);
        assertThat(stats.get("truncated")).isEqualTo(false);
        assertThat(stats.get("truncationReason")).isNull();
        assertThat(stats.get("messageCount")).isEqualTo(5);
    }

    // 22. Statistics stopped by scan budget
    @Test
    @DisplayName("22. Statistics stopped by scan budget")
    void statisticsStoppedByScanBudget() {
        setupPartitions(1, 100);
        service.setMaxScanRecords(15);

        List<ConsumerRecord<byte[], byte[]>> recs = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            recs.add(record(0, i, 100L + i, "k", "v"));
        }

        when(mockConsumer.poll(any(Duration.class))).thenReturn(recordsOf(recs.toArray(new ConsumerRecord[0])));

        var stats = service.analyzeTopic(clusterId, topic, 10);
        // Note: limit is 10, maxScanRecords is 15 -> scanBudget is max(10, 15) = 15.
        // It scans 15 and stops due to scan budget
        assertThat(stats.get("truncated")).isEqualTo(true);
        assertThat(stats.get("truncationReason")).isEqualTo("Global scan budget reached");
    }

    // 23. Statistics stopped by memory budget
    @Test
    @DisplayName("23. Statistics stopped by memory budget")
    void statisticsStoppedByMemoryBudget() {
        setupPartitions(1, 50);
        service.setMaxRetainedBytes(500L);

        when(mockConsumer.poll(any(Duration.class)))
                .thenReturn(recordsOf(record(0, 0, 100L, "k", "x".repeat(600))));

        var stats = service.analyzeTopic(clusterId, topic, 10);
        assertThat(stats.get("truncated")).isEqualTo(true);
        assertThat(stats.get("truncationReason")).isEqualTo("Retained byte budget reached");
    }

    // 24. Statistics stopped by deadline
    @Test
    @DisplayName("24. Statistics stopped by deadline")
    void statisticsStoppedByDeadline() {
        setupPartitions(1, 50);
        AtomicLong time = new AtomicLong(0L);
        service.setNanoTimeSupplier(time::get);
        service.setBrowserTimeoutMillis(1000L);

        when(mockConsumer.poll(any(Duration.class))).thenAnswer(inv -> {
            time.set(2_000_000_000L);
            return ConsumerRecords.empty();
        });

        var stats = service.analyzeTopic(clusterId, topic, 10);
        assertThat(stats.get("truncated")).isEqualTo(true);
        assertThat(stats.get("truncationReason")).isEqualTo("Execution deadline exceeded");
    }

    // 25. Verify truncated is not incorrectly set merely because result count equals requested limit
    @Test
    @DisplayName("25. Truncated is false when result count equals limit and all partitions reached end")
    void truncatedNotSetMerelyBecauseCountEqualsLimit() {
        setupPartitions(1, 10);
        TopicPartition tp0 = new TopicPartition(topic, 0);
        when(mockConsumer.position(tp0)).thenReturn(10L);

        List<ConsumerRecord<byte[], byte[]>> recs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            recs.add(record(0, i, 100L + i, "k" + i, "v" + i));
        }

        when(mockConsumer.poll(any(Duration.class)))
                .thenReturn(recordsOf(recs.toArray(new ConsumerRecord[0])))
                .thenReturn(ConsumerRecords.empty());

        var stats = service.analyzeTopic(clusterId, topic, 10);
        assertThat(stats.get("messageCount")).isEqualTo(10);
        assertThat(stats.get("sampleLimit")).isEqualTo(10);
        assertThat(stats.get("truncated")).isEqualTo(false);
        assertThat(stats.get("truncationReason")).isNull();
    }

    // 26. Concurrency control: verify configured limit is respected
    @Test
    @DisplayName("26. Concurrency control: limit is respected")
    void concurrencyLimitRespected() {
        service.setMaxConcurrentRequests(1);
        service.setBrowserTimeoutMillis(50L);

        var sem = service.getBrowserSemaphore();
        assertThat(sem.availablePermits()).isEqualTo(1);

        // Acquire the single permit
        boolean acquired = sem.tryAcquire();
        assertThat(acquired).isTrue();

        try {
            assertThatThrownBy(() -> service.readRecords(clusterId, topic, null, "newest", 10, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Kafka browser request limit reached");
        } finally {
            sem.release();
        }
    }

    // 27. Concurrency control: verify permits are released after exceptions and timeouts
    @Test
    @DisplayName("27. Concurrency control: permits released on success and failure")
    void permitsReleasedAfterSuccessAndFailure() {
        setupPartitions(1, 5);
        TopicPartition tp0 = new TopicPartition(topic, 0);
        when(mockConsumer.position(tp0)).thenReturn(5L);

        service.setMaxConcurrentRequests(2);
        var sem = service.getBrowserSemaphore();
        assertThat(sem.availablePermits()).isEqualTo(2);

        // 1. Success path
        when(mockConsumer.poll(any(Duration.class))).thenReturn(ConsumerRecords.empty());
        service.readRecords(clusterId, topic, null, "newest", 5, null);
        assertThat(sem.availablePermits()).isEqualTo(2);

        // 2. Exception path
        when(mockConsumer.poll(any(Duration.class))).thenThrow(new RuntimeException("Simulated error"));
        assertThatThrownBy(() -> service.readRecords(clusterId, topic, null, "newest", 5, null))
                .isInstanceOf(RuntimeException.class);
        assertThat(sem.availablePermits()).isEqualTo(2);
    }

    // 28. Scan Budget: Requested limit lower than scan budget
    @Test
    @DisplayName("28. Scan budget: limit lower than scan budget")
    void scanBudgetLimitLowerThanScanBudget() {
        setupPartitions(1, 100);
        TopicPartition tp0 = new TopicPartition(topic, 0);
        service.setMaxScanRecords(30);

        List<ConsumerRecord<byte[], byte[]>> batch = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            batch.add(record(0, i, 1000L + i, "k", "v"));
        }

        when(mockConsumer.poll(any(Duration.class)))
                .thenReturn(recordsOf(batch.toArray(new ConsumerRecord[0])))
                .thenReturn(ConsumerRecords.empty());
        when(mockConsumer.position(tp0)).thenReturn(20L);

        var result = service.readRecords(clusterId, topic, null, "newest", 10, null);
        assertThat(result.records()).hasSize(10);
        assertThat(result.scannedRecords()).isLessThanOrEqualTo(30);
    }

    // 29. Scan Budget: Requested limit equal to scan budget
    @Test
    @DisplayName("29. Scan budget: limit equal to scan budget")
    void scanBudgetLimitEqualToScanBudget() {
        setupPartitions(1, 100);
        service.setMaxScanRecords(20);

        List<ConsumerRecord<byte[], byte[]>> batch = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            batch.add(record(0, i, 1000L + i, "k", "v"));
        }

        when(mockConsumer.poll(any(Duration.class))).thenReturn(recordsOf(batch.toArray(new ConsumerRecord[0])));

        var result = service.readRecords(clusterId, topic, null, "newest", 20, null);
        assertThat(result.scannedRecords()).isEqualTo(20L);
        assertThat(result.truncated()).isTrue();
        assertThat(result.truncationReason()).isEqualTo("Global scan budget reached");
    }

    // 30. Scan Budget: Requested limit greater than scan budget (MUST NOT expand scanBudget)
    @Test
    @DisplayName("30. Scan budget: limit greater than scan budget capped at maxScanRecords")
    void scanBudgetLimitGreaterThanScanBudgetCapped() {
        setupPartitions(1, 200);
        service.setMaxScanRecords(15);

        List<ConsumerRecord<byte[], byte[]>> batch = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            batch.add(record(0, i, 1000L + i, "k", "v"));
        }

        when(mockConsumer.poll(any(Duration.class))).thenReturn(recordsOf(batch.toArray(new ConsumerRecord[0])));

        // Request limit 50, but maxScanRecords is 15 -> scan budget MUST remain 15!
        var result = service.readRecords(clusterId, topic, null, "newest", 50, null);
        assertThat(result.scannedRecords()).isEqualTo(15L);
        assertThat(result.records().size()).isLessThanOrEqualTo(15);
        assertThat(result.truncated()).isTrue();
        assertThat(result.truncationReason()).isEqualTo("Global scan budget reached");
    }

    // 31. Configuration: Non-default configuration values applied
    @Test
    @DisplayName("31. Configuration: non-default configuration values applied")
    void nonDefaultConfigurationValuesApplied() {
        service.setMaxScanRecords(100);
        service.setMaxRetainedBytes(5000L);
        service.setBrowserTimeoutMillis(2000L);
        service.setMaxConcurrentRequests(4);

        assertThat(service.getEffectiveMaxScanRecords()).isEqualTo(100);
        assertThat(service.getEffectiveMaxRetainedBytes()).isEqualTo(5000L);
        assertThat(service.getEffectiveBrowserTimeoutMillis()).isEqualTo(2000L);
        assertThat(service.getEffectiveMaxConcurrentRequests()).isEqualTo(4);
    }

    // 32. Configuration: Invalid configuration values fall back to safe defaults
    @Test
    @DisplayName("32. Configuration: invalid configuration values fall back safely")
    void invalidConfigurationValuesFallBackSafely() {
        service.setMaxScanRecords(0);
        assertThat(service.getEffectiveMaxScanRecords()).isEqualTo(TopicOperationsService.DEFAULT_MAX_SCAN_RECORDS);

        service.setMaxScanRecords(-50);
        assertThat(service.getEffectiveMaxScanRecords()).isEqualTo(TopicOperationsService.DEFAULT_MAX_SCAN_RECORDS);

        service.setMaxRetainedBytes(0);
        assertThat(service.getEffectiveMaxRetainedBytes()).isEqualTo(TopicOperationsService.DEFAULT_MAX_RETAINED_BYTES);

        service.setMaxRetainedBytes(-1000);
        assertThat(service.getEffectiveMaxRetainedBytes()).isEqualTo(TopicOperationsService.DEFAULT_MAX_RETAINED_BYTES);

        service.setBrowserTimeoutMillis(0);
        assertThat(service.getEffectiveBrowserTimeoutMillis()).isEqualTo(TopicOperationsService.DEFAULT_BROWSER_TIMEOUT_MS);

        service.setBrowserTimeoutMillis(-500);
        assertThat(service.getEffectiveBrowserTimeoutMillis()).isEqualTo(TopicOperationsService.DEFAULT_BROWSER_TIMEOUT_MS);

        service.setMaxConcurrentRequests(0);
        assertThat(service.getEffectiveMaxConcurrentRequests()).isEqualTo(TopicOperationsService.DEFAULT_MAX_CONCURRENT_REQUESTS);

        service.setMaxConcurrentRequests(-5);
        assertThat(service.getEffectiveMaxConcurrentRequests()).isEqualTo(TopicOperationsService.DEFAULT_MAX_CONCURRENT_REQUESTS);
    }

    // A. Non-monotonic Timestamp Test 1: Newest records with non-monotonic timestamps
    @Test
    @DisplayName("Non-monotonic timestamp Test 1: newest records with non-monotonic timestamps")
    void newestRecordsWithNonMonotonicTimestamps() {
        setupPartitions(1, 5);
        TopicPartition tp0 = new TopicPartition(topic, 0);
        when(mockConsumer.position(tp0)).thenReturn(5L);

        var r0 = record(0, 0, 1000L, "k0", "v0");
        var r1 = record(0, 1, 5000L, "k1", "v1");
        var r2 = record(0, 2, 2000L, "k2", "v2");
        var r3 = record(0, 3, 8000L, "k3", "v3");
        var r4 = record(0, 4, 3000L, "k4", "v4");

        when(mockConsumer.poll(any(Duration.class)))
                .thenReturn(recordsOf(r0, r1, r2, r3, r4))
                .thenReturn(ConsumerRecords.empty());

        var result = service.readRecords(clusterId, topic, null, "newest", 3, null);
        assertThat(result.records()).extracting(ConsumerRecord::timestamp).containsExactly(8000L, 5000L, 3000L);
        assertThat(result.records()).containsExactly(r3, r1, r4);
    }

    // A. Non-monotonic Timestamp Test 2: Oldest records with non-monotonic timestamps
    @Test
    @DisplayName("Non-monotonic timestamp Test 2: oldest records with non-monotonic timestamps")
    void oldestRecordsWithNonMonotonicTimestamps() {
        setupPartitions(1, 5);
        TopicPartition tp0 = new TopicPartition(topic, 0);
        when(mockConsumer.position(tp0)).thenReturn(5L);

        var r0 = record(0, 0, 1000L, "k0", "v0");
        var r1 = record(0, 1, 5000L, "k1", "v1");
        var r2 = record(0, 2, 2000L, "k2", "v2");
        var r3 = record(0, 3, 8000L, "k3", "v3");
        var r4 = record(0, 4, 3000L, "k4", "v4");

        when(mockConsumer.poll(any(Duration.class)))
                .thenReturn(recordsOf(r0, r1, r2, r3, r4))
                .thenReturn(ConsumerRecords.empty());

        var result = service.readRecords(clusterId, topic, null, "oldest", 3, null);
        assertThat(result.records()).extracting(ConsumerRecord::timestamp).containsExactly(1000L, 2000L, 3000L);
        assertThat(result.records()).containsExactly(r0, r2, r4);
    }

    // A. Non-monotonic Timestamp Test 3: Newest records across multiple partitions
    @Test
    @DisplayName("Non-monotonic timestamp Test 3: newest records across multiple partitions")
    void newestRecordsAcrossMultiplePartitions() {
        setupPartitions(2, 3);
        when(mockConsumer.position(new TopicPartition(topic, 0))).thenReturn(3L);
        when(mockConsumer.position(new TopicPartition(topic, 1))).thenReturn(3L);

        var p0r0 = record(0, 0, 1000L, "k", "p0r0");
        var p0r1 = record(0, 1, 9000L, "k", "p0r1");
        var p0r2 = record(0, 2, 3000L, "k", "p0r2");

        var p1r0 = record(1, 0, 7000L, "k", "p1r0");
        var p1r1 = record(1, 1, 2000L, "k", "p1r1");
        var p1r2 = record(1, 2, 8000L, "k", "p1r2");

        when(mockConsumer.poll(any(Duration.class)))
                .thenReturn(recordsOf(p0r0, p0r1, p0r2, p1r0, p1r1, p1r2))
                .thenReturn(ConsumerRecords.empty());

        var result = service.readRecords(clusterId, topic, null, "newest", 4, null);
        assertThat(result.records()).extracting(ConsumerRecord::timestamp).containsExactly(9000L, 8000L, 7000L, 3000L);
        assertThat(result.records()).containsExactly(p0r1, p1r2, p1r0, p0r2);
    }

    // A. Non-monotonic Timestamp Test 4: Oldest records across multiple partitions
    @Test
    @DisplayName("Non-monotonic timestamp Test 4: oldest records across multiple partitions")
    void oldestRecordsAcrossMultiplePartitions() {
        setupPartitions(2, 3);
        when(mockConsumer.position(new TopicPartition(topic, 0))).thenReturn(3L);
        when(mockConsumer.position(new TopicPartition(topic, 1))).thenReturn(3L);

        var p0r0 = record(0, 0, 1000L, "k", "p0r0");
        var p0r1 = record(0, 1, 9000L, "k", "p0r1");
        var p0r2 = record(0, 2, 3000L, "k", "p0r2");

        var p1r0 = record(1, 0, 7000L, "k", "p1r0");
        var p1r1 = record(1, 1, 2000L, "k", "p1r1");
        var p1r2 = record(1, 2, 8000L, "k", "p1r2");

        when(mockConsumer.poll(any(Duration.class)))
                .thenReturn(recordsOf(p0r0, p0r1, p0r2, p1r0, p1r1, p1r2))
                .thenReturn(ConsumerRecords.empty());

        var result = service.readRecords(clusterId, topic, null, "oldest", 4, null);
        assertThat(result.records()).extracting(ConsumerRecord::timestamp).containsExactly(1000L, 2000L, 3000L, 7000L);
        assertThat(result.records()).containsExactly(p0r0, p1r1, p0r2, p1r0);
    }

    // A. Non-monotonic Timestamp Test 5: Equal timestamps across partitions
    @Test
    @DisplayName("Non-monotonic timestamp Test 5: equal timestamps across partitions deterministic ordering")
    void equalTimestampsAcrossPartitionsDeterministicOrdering() {
        setupPartitions(3, 2);
        for (int p = 0; p < 3; p++) {
            when(mockConsumer.position(new TopicPartition(topic, p))).thenReturn(2L);
        }

        var p0r0 = record(0, 0, 5000L, "k", "p0r0");
        var p0r1 = record(0, 1, 5000L, "k", "p0r1");
        var p1r0 = record(1, 0, 5000L, "k", "p1r0");
        var p1r1 = record(1, 1, 5000L, "k", "p1r1");
        var p2r0 = record(2, 0, 5000L, "k", "p2r0");
        var p2r1 = record(2, 1, 500L,  "k", "p2r1");

        when(mockConsumer.poll(any(Duration.class)))
                .thenReturn(recordsOf(p0r0, p0r1, p1r0, p1r1, p2r0, p2r1))
                .thenReturn(ConsumerRecords.empty());

        // For oldest: timestamp ASC, partition ASC, offset ASC
        var oldest = service.readRecords(clusterId, topic, null, "oldest", 4, null);
        assertThat(oldest.records()).containsExactly(p2r1, p0r0, p0r1, p1r0);

        when(mockConsumer.poll(any(Duration.class)))
                .thenReturn(recordsOf(p0r0, p0r1, p1r0, p1r1, p2r0, p2r1))
                .thenReturn(ConsumerRecords.empty());

        // For newest: timestamp DESC, partition DESC, offset DESC
        var newest = service.readRecords(clusterId, topic, null, "newest", 4, null);
        assertThat(newest.records()).containsExactly(p2r0, p1r1, p1r0, p0r1);
    }

    // A. Non-monotonic Timestamp Test 6: Scan-budget exhaustion during timestamp ordering
    @Test
    @DisplayName("Non-monotonic timestamp Test 6: scan-budget exhaustion during timestamp ordering")
    void scanBudgetExhaustionDuringTimestampOrdering() {
        setupPartitions(1, 10);
        service.setMaxScanRecords(3); // Small scan budget

        var r0 = record(0, 0, 1000L, "k0", "v0");
        var r1 = record(0, 1, 5000L, "k1", "v1");
        var r2 = record(0, 2, 2000L, "k2", "v2");
        var r3 = record(0, 3, 8000L, "k3", "v3");
        var r4 = record(0, 4, 3000L, "k4", "v4");
        var r5 = record(0, 5, 9000L, "k5", "v5");

        when(mockConsumer.poll(any(Duration.class))).thenReturn(recordsOf(r0, r1, r2, r3, r4, r5));

        var result = service.readRecords(clusterId, topic, null, "newest", 5, null);

        // 1. Scanned records do not exceed configured budget
        assertThat(result.scannedRecords()).isEqualTo(3L);
        // 2. Result contains no more than requested limit
        assertThat(result.records().size()).isLessThanOrEqualTo(3);
        // 3. Truncated is true
        assertThat(result.truncated()).isTrue();
        // 4. Truncation reason identifies scan-budget limit
        assertThat(result.truncationReason()).isEqualTo("Global scan budget reached");
        // 5. Returned records are correctly ordered among the records actually scanned (r0, r1, r2)
        // Among r0 (1000), r1 (5000), r2 (2000), newest order is r1 (5000), r2 (2000), r0 (1000)
        assertThat(result.records()).containsExactly(r1, r2, r0);
    }

    // Deterministic tie-breaking: Equal timestamps on same partition
    @Test
    @DisplayName("Deterministic tie-breaking: equal timestamps on same partition")
    void equalTimestampsSamePartition() {
        setupPartitions(1, 2);
        TopicPartition tp0 = new TopicPartition(topic, 0);
        when(mockConsumer.position(tp0)).thenReturn(2L);

        var rEarly = record(0, 3, 1000L, "k", "early");
        var rLate  = record(0, 8, 1000L, "k", "late");

        when(mockConsumer.poll(any(Duration.class)))
                .thenReturn(recordsOf(rEarly, rLate))
                .thenReturn(ConsumerRecords.empty());

        // For oldest: lower offset first -> rEarly, rLate
        var oldest = service.readRecords(clusterId, topic, null, "oldest", 2, null);
        assertThat(oldest.records()).containsExactly(rEarly, rLate);

        when(mockConsumer.poll(any(Duration.class)))
                .thenReturn(recordsOf(rEarly, rLate))
                .thenReturn(ConsumerRecords.empty());

        // For newest: higher offset first -> rLate, rEarly
        var newest = service.readRecords(clusterId, topic, null, "newest", 2, null);
        assertThat(newest.records()).containsExactly(rLate, rEarly);
    }

    // Search Normalization: null, empty, and whitespace strings
    @Test
    @DisplayName("Search normalization: null, empty, whitespace, and trimmed values")
    void searchNormalizationBehaviors() {
        assertThat(TopicOperationsService.normalizeSearch(null)).isNull();
        assertThat(TopicOperationsService.normalizeSearch("")).isNull();
        assertThat(TopicOperationsService.normalizeSearch("   ")).isNull();
        assertThat(TopicOperationsService.normalizeSearch("\t \n  ")).isNull();
        assertThat(TopicOperationsService.normalizeSearch("  order-id  ")).isEqualTo("order-id");
        assertThat(TopicOperationsService.normalizeSearch("customer")).isEqualTo("customer");
    }

    // Blank Search behaves like no search filter and does not expand scan range
    @Test
    @DisplayName("Blank search behaves like no search filter")
    void blankSearchBehavesLikeNoFilter() {
        setupPartitions(1, 50);
        TopicPartition tp0 = new TopicPartition(topic, 0);
        when(mockConsumer.position(tp0)).thenReturn(5L);

        List<ConsumerRecord<byte[], byte[]>> recs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            recs.add(record(0, i, 100L + i, "k" + i, "v" + i));
        }

        when(mockConsumer.poll(any(Duration.class)))
                .thenReturn(recordsOf(recs.toArray(new ConsumerRecord[0])))
                .thenReturn(ConsumerRecords.empty());

        // Call with whitespace search
        var responseWhitespace = service.getMessages(clusterId, topic, null, "oldest", 5, "   ");
        assertThat(responseWhitespace.get("count")).isEqualTo(5);

        when(mockConsumer.poll(any(Duration.class)))
                .thenReturn(recordsOf(recs.toArray(new ConsumerRecord[0])))
                .thenReturn(ConsumerRecords.empty());

        // Call with empty search
        var responseEmpty = service.getMessages(clusterId, topic, null, "oldest", 5, "");
        assertThat(responseEmpty.get("count")).isEqualTo(5);
    }

    // Trimmed search filter matches needle accurately
    @Test
    @DisplayName("Search with spaces matches trimmed search needle")
    void searchWithSpacesMatchesTrimmedNeedle() {
        setupPartitions(1, 4);
        TopicPartition tp0 = new TopicPartition(topic, 0);
        when(mockConsumer.position(tp0)).thenReturn(4L);

        var r1 = record(0, 0, 100L, "order-101", "created");
        var r2 = record(0, 1, 200L, "customer-55", "login");
        var r3 = record(0, 2, 300L, "order-102", "fulfilled");
        var r4 = record(0, 3, 400L, "payment-99", "processed");

        when(mockConsumer.poll(any(Duration.class)))
                .thenReturn(recordsOf(r1, r2, r3, r4))
                .thenReturn(ConsumerRecords.empty());

        var response = service.getMessages(clusterId, topic, null, "newest", 10, "  order  ");
        assertThat(response.get("count")).isEqualTo(2);
        List<?> messages = (List<?>) response.get("messages");
        assertThat(messages).hasSize(2);
    }
}
