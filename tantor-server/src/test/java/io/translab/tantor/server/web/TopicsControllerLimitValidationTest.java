package io.translab.tantor.server.web;

import io.translab.tantor.server.audit.AuditService;
import io.translab.tantor.server.repository.ClusterRepository;
import io.translab.tantor.server.service.ActivityAlertService;
import io.translab.tantor.server.service.KafkaAdminService;
import io.translab.tantor.server.service.PartitionCacheService;
import io.translab.tantor.server.service.TopicOperationsService;
import io.translab.tantor.server.util.RoleAuthenticationUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TopicsControllerLimitValidationTest {

    private TopicOperationsService topicOperationsService;
    private TopicsController controller;
    private UUID clusterId;
    private final String topic = "my-topic";

    @BeforeEach
    void setUp() {
        KafkaAdminService kafkaAdmin = mock(KafkaAdminService.class);
        topicOperationsService = mock(TopicOperationsService.class);
        PartitionCacheService partitionCache = mock(PartitionCacheService.class);
        ClusterRepository clusters = mock(ClusterRepository.class);
        AuditService audit = mock(AuditService.class);
        RoleAuthenticationUtil roles = mock(RoleAuthenticationUtil.class);
        ActivityAlertService activities = mock(ActivityAlertService.class);

        clusterId = UUID.randomUUID();
        controller = new TopicsController(
                kafkaAdmin, topicOperationsService, partitionCache, clusters, audit, roles, activities);
    }

    @Test
    @DisplayName("Negative or zero limit on /messages returns 400 Bad Request")
    void messagesRejectsNonPositiveLimit() {
        var resZero = controller.getMessages(clusterId, topic, null, "newest", 0, null);
        assertThat(resZero.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        var resNegative = controller.getMessages(clusterId, topic, null, "newest", -10, null);
        assertThat(resNegative.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        verifyNoInteractions(topicOperationsService);
    }

    @Test
    @DisplayName("Valid limit on /messages delegates to service")
    void messagesAcceptsPositiveLimit() {
        when(topicOperationsService.getMessages(clusterId, topic, null, "newest", 100, null))
                .thenReturn(Map.of("count", 0));

        var response = controller.getMessages(clusterId, topic, null, "newest", 100, null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(topicOperationsService).getMessages(clusterId, topic, null, "newest", 100, null);
    }

    @Test
    @DisplayName("Negative or zero limit on /statistics returns 400 Bad Request")
    void statisticsRejectsNonPositiveLimit() {
        var resZero = controller.getStatistics(clusterId, topic, 0);
        assertThat(resZero.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        var resNegative = controller.getStatistics(clusterId, topic, -5);
        assertThat(resNegative.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        verifyNoInteractions(topicOperationsService);
    }

    @Test
    @DisplayName("Valid limit on /statistics delegates to service")
    void statisticsAcceptsPositiveLimit() {
        when(topicOperationsService.analyzeTopic(clusterId, topic, 5000))
                .thenReturn(Map.of("messageCount", 0));

        var response = controller.getStatistics(clusterId, topic, 5000);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(topicOperationsService).analyzeTopic(clusterId, topic, 5000);
    }
}

