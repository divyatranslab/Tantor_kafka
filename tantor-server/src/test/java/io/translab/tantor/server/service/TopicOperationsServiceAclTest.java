package io.translab.tantor.server.service;

import io.translab.tantor.server.audit.AuditService;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeAclsResult;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.acl.AccessControlEntry;
import org.apache.kafka.common.acl.AclBinding;
import org.apache.kafka.common.acl.AclBindingFilter;
import org.apache.kafka.common.acl.AclOperation;
import org.apache.kafka.common.acl.AclPermissionType;
import org.apache.kafka.common.errors.ClusterAuthorizationException;
import org.apache.kafka.common.errors.DisconnectException;
import org.apache.kafka.common.resource.PatternType;
import org.apache.kafka.common.resource.ResourcePattern;
import org.apache.kafka.common.resource.ResourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TopicOperationsServiceAclTest {

    @Mock
    private KafkaAdminService kafkaAdminService;

    @Mock
    private AuditService auditService;

    @Mock
    private AdminClient adminClient;

    @Mock
    private DescribeAclsResult describeAclsResult;

    @Captor
    private ArgumentCaptor<AclBindingFilter> filterCaptor;

    private TopicOperationsService service;
    private final UUID clusterId = UUID.randomUUID();
    private final String topicName = "orders";

    @BeforeEach
    void setUp() {
        service = new TopicOperationsService(kafkaAdminService, auditService);
        when(kafkaAdminService.getAdminClient(clusterId)).thenReturn(adminClient);
    }

    @Test
    @DisplayName("Test 1: Exact LITERAL topic ACL is included in the returned list")
    void exactLiteralTopicAclIsIncluded() {
        AclBinding exactAcl = createAcl(topicName, PatternType.LITERAL, "User:alice", "*", AclOperation.READ, AclPermissionType.ALLOW);
        setupDescribeAcls(List.of(exactAcl));

        List<Map<String, Object>> result = service.getTopicAcls(clusterId, topicName);

        assertThat(result).hasSize(1);
        assertThat(result.get(0))
                .containsEntry("resourceName", topicName)
                .containsEntry("patternType", "LITERAL")
                .containsEntry("principal", "User:alice")
                .containsEntry("operation", "READ")
                .containsEntry("permissionType", "ALLOW")
                .containsEntry("host", "*");
    }

    @Test
    @DisplayName("Test 2: Applicable PREFIXED topic ACL is included and unrelated prefix is excluded")
    void applicablePrefixedTopicAclIsIncludedAndUnrelatedPrefixExcluded() {
        AclBinding applicablePrefixed = createAcl("ord", PatternType.PREFIXED, "User:producer", "*", AclOperation.WRITE, AclPermissionType.ALLOW);
        AclBinding unrelatedPrefixed = createAcl("payment", PatternType.PREFIXED, "User:payments-service", "*", AclOperation.WRITE, AclPermissionType.ALLOW);
        AclBinding longerPrefixed = createAcl("orders-extra", PatternType.PREFIXED, "User:extra", "*", AclOperation.READ, AclPermissionType.ALLOW);

        setupDescribeAcls(List.of(applicablePrefixed, unrelatedPrefixed, longerPrefixed));

        List<Map<String, Object>> result = service.getTopicAcls(clusterId, topicName);

        assertThat(result)
                .as("Applicable PREFIXED ACL ('ord') matching topic '%s' must be included", topicName)
                .extracting(m -> m.get("resourceName"))
                .containsExactly("ord");
        assertThat(result)
                .extracting(m -> m.get("patternType"))
                .containsExactly("PREFIXED");
    }

    @Test
    @DisplayName("Test 3: Wildcard LITERAL topic ACL is included")
    void wildcardTopicAclIsIncluded() {
        AclBinding wildcardAcl = createAcl("*", PatternType.LITERAL, "User:admin", "*", AclOperation.ALL, AclPermissionType.ALLOW);
        setupDescribeAcls(List.of(wildcardAcl));

        List<Map<String, Object>> result = service.getTopicAcls(clusterId, topicName);

        assertThat(result).hasSize(1);
        assertThat(result.get(0))
                .containsEntry("resourceName", "*")
                .containsEntry("patternType", "LITERAL")
                .containsEntry("principal", "User:admin");
    }

    @Test
    @DisplayName("Test 4: Unrelated LITERAL topic ACL is excluded")
    void unrelatedTopicAclIsExcluded() {
        AclBinding unrelatedAcl = createAcl("payments", PatternType.LITERAL, "User:bob", "*", AclOperation.READ, AclPermissionType.ALLOW);
        setupDescribeAcls(List.of(unrelatedAcl));

        List<Map<String, Object>> result = service.getTopicAcls(clusterId, topicName);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Test 5: Unrelated resource types (GROUP, CLUSTER, TRANSACTIONAL_ID) are excluded")
    void unrelatedResourceTypesAreExcluded() {
        AclBinding groupAcl = createResourceAcl(ResourceType.GROUP, topicName, PatternType.LITERAL, "User:consumer", "*", AclOperation.READ, AclPermissionType.ALLOW);
        AclBinding clusterAcl = createResourceAcl(ResourceType.CLUSTER, "kafka-cluster", PatternType.LITERAL, "User:admin", "*", AclOperation.CLUSTER_ACTION, AclPermissionType.ALLOW);
        AclBinding txAcl = createResourceAcl(ResourceType.TRANSACTIONAL_ID, "orders-tx", PatternType.LITERAL, "User:tx", "*", AclOperation.WRITE, AclPermissionType.ALLOW);

        setupDescribeAcls(List.of(groupAcl, clusterAcl, txAcl));

        List<Map<String, Object>> result = service.getTopicAcls(clusterId, topicName);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Test 6: Multiple applicable ACLs with different attributes are included without duplicates")
    void multipleApplicableAclsWithDifferentAttributesAreIncluded() {
        AclBinding readAllow = createAcl(topicName, PatternType.LITERAL, "User:alice", "192.168.1.10", AclOperation.READ, AclPermissionType.ALLOW);
        AclBinding writeDeny = createAcl(topicName, PatternType.LITERAL, "User:bob", "*", AclOperation.WRITE, AclPermissionType.DENY);
        AclBinding adminWildcard = createAcl("*", PatternType.LITERAL, "User:admin", "192.168.1.20", AclOperation.ALL, AclPermissionType.ALLOW);

        setupDescribeAcls(List.of(readAllow, writeDeny, adminWildcard));

        List<Map<String, Object>> result = service.getTopicAcls(clusterId, topicName);

        assertThat(result).hasSize(3);
        assertThat(result)
                .extracting(m -> m.get("principal"))
                .containsExactlyInAnyOrder("User:alice", "User:bob", "User:admin");
        assertThat(result)
                .extracting(m -> m.get("operation"))
                .containsExactlyInAnyOrder("READ", "WRITE", "ALL");
        assertThat(result)
                .extracting(m -> m.get("permissionType"))
                .containsExactlyInAnyOrder("ALLOW", "DENY", "ALLOW");
        assertThat(result)
                .extracting(m -> m.get("host"))
                .containsExactlyInAnyOrder("192.168.1.10", "*", "192.168.1.20");
    }

    @Test
    @DisplayName("Test 7: Empty ACL result returns empty list without exception")
    void emptyAclResultReturnsEmptyListWithoutException() {
        setupDescribeAcls(List.of());

        List<Map<String, Object>> result = service.getTopicAcls(clusterId, topicName);

        assertThat(result).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("Test 8: Kafka authorization failure propagates exception and preserves cause")
    @SuppressWarnings("unchecked")
    void kafkaAuthorizationFailurePropagatesExceptionAndPreservesCause() throws Exception {
        when(adminClient.describeAcls(any(AclBindingFilter.class))).thenReturn(describeAclsResult);
        KafkaFuture<Collection<AclBinding>> failingFuture = mock(KafkaFuture.class);
        ClusterAuthorizationException authException = new ClusterAuthorizationException("Cluster authorization failed");
        when(failingFuture.get()).thenThrow(new ExecutionException(authException));
        when(describeAclsResult.values()).thenReturn(failingFuture);

        assertThatThrownBy(() -> service.getTopicAcls(clusterId, topicName))
                .isInstanceOf(RuntimeException.class)
                .hasMessageStartingWith("Failed to load topic ACLs")
                .hasMessageContaining("Cluster authorization failed")
                .hasRootCause(authException);
    }

    @Test
    @DisplayName("Test 9: Kafka execution failure propagates exception and preserves cause")
    @SuppressWarnings("unchecked")
    void kafkaExecutionFailurePropagatesExceptionAndPreservesCause() throws Exception {
        when(adminClient.describeAcls(any(AclBindingFilter.class))).thenReturn(describeAclsResult);
        KafkaFuture<Collection<AclBinding>> failingFuture = mock(KafkaFuture.class);
        DisconnectException disconnectException = new DisconnectException("Connection to broker lost");
        when(failingFuture.get()).thenThrow(new ExecutionException(disconnectException));
        when(describeAclsResult.values()).thenReturn(failingFuture);

        assertThatThrownBy(() -> service.getTopicAcls(clusterId, topicName))
                .isInstanceOf(RuntimeException.class)
                .hasMessageStartingWith("Failed to load topic ACLs")
                .hasMessageContaining("Connection to broker lost")
                .hasRootCause(disconnectException);
    }

    @Test
    @DisplayName("Test 10: Interrupted ACL lookup restores interrupt flag and throws exception")
    @SuppressWarnings("unchecked")
    void interruptedAclLookupRestoresInterruptFlagAndThrowsException() throws Exception {
        when(adminClient.describeAcls(any(AclBindingFilter.class))).thenReturn(describeAclsResult);
        KafkaFuture<Collection<AclBinding>> interruptedFuture = mock(KafkaFuture.class);
        when(interruptedFuture.get()).thenThrow(new InterruptedException("Interrupted while waiting for ACLs"));
        when(describeAclsResult.values()).thenReturn(interruptedFuture);

        try {
            assertThatThrownBy(() -> service.getTopicAcls(clusterId, topicName))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("Interrupted while loading topic ACLs");

            assertThat(Thread.currentThread().isInterrupted())
                    .as("Thread interrupt flag must be restored")
                    .isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("Test 11: Service must not issue unrestricted AclBindingFilter.ANY query")
    void describeAclsDoesNotUseUnrestrictedAnyFilter() {
        setupDescribeAcls(List.of());

        service.getTopicAcls(clusterId, topicName);

        verify(adminClient, atLeastOnce()).describeAcls(filterCaptor.capture());
        List<AclBindingFilter> capturedFilters = filterCaptor.getAllValues();

        assertThat(capturedFilters)
                .as("Service must not query Kafka with AclBindingFilter.ANY")
                .noneMatch(filter -> filter.equals(AclBindingFilter.ANY));

        assertThat(capturedFilters)
                .as("Service must not query Kafka with unrestricted ResourceType.ANY")
                .noneMatch(filter -> filter.patternFilter().resourceType() == ResourceType.ANY);
    }

    @Test
    @DisplayName("Test 12: Filter sent to Kafka is scoped to TOPIC and covers exact, prefix, and wildcard matching")
    void describeAclsFilterCorrectlyScopedToTopicAndMatchingSemantics() {
        setupDescribeAcls(List.of());

        service.getTopicAcls(clusterId, topicName);

        verify(adminClient, atLeastOnce()).describeAcls(filterCaptor.capture());
        List<AclBindingFilter> capturedFilters = filterCaptor.getAllValues();

        assertThat(capturedFilters)
                .as("At least one scoped filter must be sent to Kafka")
                .isNotEmpty();

        // 1. All filters must restrict resource type to TOPIC (never ANY or non-TOPIC)
        assertThat(capturedFilters)
                .as("Every filter sent to Kafka must restrict resource type to TOPIC")
                .allMatch(f -> f.patternFilter().resourceType() == ResourceType.TOPIC);

        // 2. Sample bindings to verify matching semantics
        AclBinding exactTopicAcl = createAcl(topicName, PatternType.LITERAL, "User:alice", "*", AclOperation.READ, AclPermissionType.ALLOW);
        AclBinding wildcardAcl = createAcl("*", PatternType.LITERAL, "User:admin", "*", AclOperation.ALL, AclPermissionType.ALLOW);
        AclBinding prefixedAcl = createAcl("ord", PatternType.PREFIXED, "User:producer", "*", AclOperation.WRITE, AclPermissionType.ALLOW);

        AclBinding unrelatedTopicAcl = createAcl("payments", PatternType.LITERAL, "User:bob", "*", AclOperation.READ, AclPermissionType.ALLOW);
        AclBinding unrelatedPrefixAcl = createAcl("payment", PatternType.PREFIXED, "User:bob", "*", AclOperation.READ, AclPermissionType.ALLOW);
        AclBinding unrelatedResourceTypeAcl = createResourceAcl(ResourceType.GROUP, topicName, PatternType.LITERAL, "User:cg", "*", AclOperation.READ, AclPermissionType.ALLOW);

        // Combined coverage must include applicable patterns
        assertThat(capturedFilters.stream().anyMatch(f -> f.matches(exactTopicAcl)))
                .as("Combined filter coverage must match exact literal topic ACL")
                .isTrue();
        assertThat(capturedFilters.stream().anyMatch(f -> f.matches(wildcardAcl)))
                .as("Combined filter coverage must match wildcard topic ACL")
                .isTrue();
        assertThat(capturedFilters.stream().anyMatch(f -> f.matches(prefixedAcl)))
                .as("Combined filter coverage must match applicable prefixed topic ACL")
                .isTrue();

        // Combined coverage must exclude unrelated patterns
        assertThat(capturedFilters.stream().noneMatch(f -> f.matches(unrelatedTopicAcl)))
                .as("Filters must not match unrelated topic ACL")
                .isTrue();
        assertThat(capturedFilters.stream().noneMatch(f -> f.matches(unrelatedPrefixAcl)))
                .as("Filters must not match unrelated prefix ACL")
                .isTrue();
        assertThat(capturedFilters.stream().noneMatch(f -> f.matches(unrelatedResourceTypeAcl)))
                .as("Filters must not match non-TOPIC resource type ACL")
                .isTrue();
    }

    @Test
    @DisplayName("Test 13: Avoid duplicate results when multiple queries or Kafka responses contain overlapping ACLs")
    void duplicateAclBindingsAreDeduplicatedInFinalResult() {
        AclBinding acl = createAcl(topicName, PatternType.LITERAL, "User:alice", "*", AclOperation.READ, AclPermissionType.ALLOW);
        setupDescribeAcls(List.of(acl, acl));

        List<Map<String, Object>> result = service.getTopicAcls(clusterId, topicName);

        assertThat(result)
                .as("Duplicate ACL entries must be deduplicated in the final result")
                .hasSize(1);
    }

    @Test
    @DisplayName("Response Compatibility: Map entries retain all expected fields matching Kafka AclBinding")
    void responseMapRetainsAllExpectedFieldsMatchingKafkaBinding() {
        AclBinding binding = createAcl(
                topicName,
                PatternType.LITERAL,
                "User:service-account",
                "10.240.0.15",
                AclOperation.WRITE,
                AclPermissionType.ALLOW
        );
        setupDescribeAcls(List.of(binding));

        List<Map<String, Object>> result = service.getTopicAcls(clusterId, topicName);

        assertThat(result).hasSize(1);
        Map<String, Object> row = result.get(0);

        assertThat(row.keySet())
                .as("Returned map must contain exactly the expected API keys")
                .containsExactly("principal", "host", "operation", "permissionType", "patternType", "resourceName");

        assertThat(row.get("principal")).isEqualTo(binding.entry().principal());
        assertThat(row.get("host")).isEqualTo(binding.entry().host());
        assertThat(row.get("operation")).isEqualTo(binding.entry().operation().name());
        assertThat(row.get("permissionType")).isEqualTo(binding.entry().permissionType().name());
        assertThat(row.get("patternType")).isEqualTo(binding.pattern().patternType().name());
        assertThat(row.get("resourceName")).isEqualTo(binding.pattern().name());
    }

    private void setupDescribeAcls(Collection<AclBinding> bindings) {
        when(adminClient.describeAcls(any(AclBindingFilter.class))).thenReturn(describeAclsResult);
        when(describeAclsResult.values()).thenReturn(KafkaFuture.completedFuture(bindings));
    }

    private AclBinding createAcl(
            String resourceName,
            PatternType patternType,
            String principal,
            String host,
            AclOperation operation,
            AclPermissionType permissionType) {
        return new AclBinding(
                new ResourcePattern(ResourceType.TOPIC, resourceName, patternType),
                new AccessControlEntry(principal, host, operation, permissionType)
        );
    }

    private AclBinding createResourceAcl(
            ResourceType resourceType,
            String resourceName,
            PatternType patternType,
            String principal,
            String host,
            AclOperation operation,
            AclPermissionType permissionType) {
        return new AclBinding(
                new ResourcePattern(resourceType, resourceName, patternType),
                new AccessControlEntry(principal, host, operation, permissionType)
        );
    }
}

