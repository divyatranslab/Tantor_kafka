package io.translab.tantor.server.service;



import io.translab.tantor.server.domain.*;

import io.translab.tantor.server.dto.ClusterOverviewDto;

import io.translab.tantor.server.repository.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;

import org.junit.jupiter.api.Test;

import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.InjectMocks;

import org.mockito.Mock;

import org.mockito.junit.jupiter.MockitoExtension;



import java.time.OffsetDateTime;

import java.util.*;



import static org.junit.jupiter.api.Assertions.*;

import static org.mockito.ArgumentMatchers.any;

import static org.mockito.Mockito.*;

import static org.mockito.ArgumentMatchers.anyString;

import static org.mockito.ArgumentMatchers.any;



@ExtendWith(MockitoExtension.class)

public class ClusterHealthViewServiceTest {



    @Mock private ObjectMapper objectMapper;

    @Mock private BrokerMetricsCacheService brokerMetricsCacheService;

    @Mock private KafkaAdminService kafkaAdminService;

    @Mock private HostRepository hostRepository;

    @Mock private HostStatusService hostStatusService;

    @Mock private DiscoveryAgentRepository discoveryAgentRepository;

    @Mock private ExternalClusterNodeRepository externalClusterNodeRepository;

    @Mock private ClusterValidationService clusterValidationService;

    @Mock private ClusterOverviewService clusterOverviewService;



    @InjectMocks

    private ClusterHealthViewService clusterHealthViewService;



    @Test

    void testNormalInternalClusterMapping() {

        Cluster internalCluster = new Cluster();

        internalCluster.setId(UUID.randomUUID());

        internalCluster.setName("TestInternal");

        internalCluster.setMode("INTERNAL");



        Map<String, Object> result = clusterHealthViewService.mapInternalCluster(internalCluster, false);



        assertNotNull(result);

        assertEquals("TestInternal", result.get("name"));

        assertEquals("INTERNAL", result.get("mode"));

    }



    @Test

    void testModeExternalRowExcludedFromInternalMapping() {

        Cluster externalModeCluster = new Cluster();

        externalModeCluster.setMode("EXTERNAL");



        Map<String, Object> result = clusterHealthViewService.mapInternalCluster(externalModeCluster, false);

        assertNull(result, "EXTERNAL mode cluster should be excluded (mapped to null)");

    }



    @Test

    void testInternalListOrderingIsPreserved() {

        Cluster c1 = new Cluster(); c1.setMode("INTERNAL"); c1.setId(UUID.randomUUID()); c1.setName("C1");

        Cluster c2 = new Cluster(); c2.setMode("EXTERNAL"); c2.setId(UUID.randomUUID()); c2.setName("C2");

        Cluster c3 = new Cluster(); c3.setMode("INTERNAL"); c3.setId(UUID.randomUUID()); c3.setName("C3");



        List<Map<String, Object>> results = clusterHealthViewService.mapInternalClusters(Arrays.asList(c1, c2, c3));



        assertEquals(2, results.size());

        assertEquals("C1", results.get(0).get("name"));

        assertEquals("C3", results.get(1).get("name"));

    }



    @Test

    void testExternalLiveOverviewSucceeds() throws Exception {

        ExternalCluster extCluster = new ExternalCluster();

        extCluster.setId(UUID.randomUUID());

        extCluster.setName("TestExt");



        ClusterOverviewDto mockLiveOverview = new ClusterOverviewDto();

        mockLiveOverview.setKafkaVersion("3.6.0");

        mockLiveOverview.setOriginType("EXTERNAL");



        when(externalClusterNodeRepository.findByClusterId(extCluster.getId())).thenReturn(Collections.emptyList());


        lenient().when(clusterValidationService.parseKafkaVersion(any())).thenReturn(new int[]{3, 6, 0});


        lenient().when(clusterValidationService.parseKafkaVersion(any())).thenReturn(new int[]{3, 6, 0});

        when(clusterOverviewService.getOverview(extCluster.getId())).thenReturn(mockLiveOverview);



        ClusterOverviewDto result = clusterHealthViewService.getExternalClusterOverview(extCluster);



        assertNotNull(result);

        assertEquals("EXTERNAL", result.getOriginType());

        assertEquals("3.6.0", result.getKafkaVersion());

    }



    @Test

    void testExternalLiveOverviewFailureReturnsSavedMetadata() throws Exception {

        ExternalCluster extCluster = new ExternalCluster();

        extCluster.setId(UUID.randomUUID());

        extCluster.setName("SavedName");

        extCluster.setKafkaVersion("2.8.0");



        when(externalClusterNodeRepository.findByClusterId(extCluster.getId())).thenReturn(Collections.emptyList());


        lenient().when(clusterValidationService.parseKafkaVersion(any())).thenReturn(new int[]{3, 6, 0});


        lenient().when(clusterValidationService.parseKafkaVersion(any())).thenReturn(new int[]{3, 6, 0});

        when(clusterOverviewService.getOverview(extCluster.getId())).thenThrow(new RuntimeException("Connection failed"));



        ClusterOverviewDto result = clusterHealthViewService.getExternalClusterOverview(extCluster);



        assertNotNull(result);

        assertEquals("EXTERNAL", result.getOriginType());

        assertEquals("SavedName", result.getName());

        assertEquals("Not reported", result.getControllerType());

        assertTrue(result.getWarnings() != null && result.getWarnings().contains("Live Kafka metadata is unavailable. Showing the last saved external cluster metadata."));

    }



    @Test

    void testBrokerControllerAndNodePathFieldsAreMapped() {

        ExternalCluster extCluster = new ExternalCluster();

        extCluster.setId(UUID.randomUUID());

        extCluster.setInstallPath("/opt/kafka");



        ExternalClusterNode brokerNode = new ExternalClusterNode();

        brokerNode.setNodeId(1);

        brokerNode.setHost("broker1");

        brokerNode.setIsBroker(true);

        brokerNode.setIsController(false);



        ExternalClusterNode controllerNode = new ExternalClusterNode();

        controllerNode.setNodeId(2);

        controllerNode.setHost("controller1");

        controllerNode.setIsBroker(false);

        controllerNode.setIsController(true);



        when(externalClusterNodeRepository.findByClusterId(extCluster.getId())).thenReturn(Arrays.asList(brokerNode, controllerNode));


        when(clusterValidationService.parseKafkaVersion(anyString())).thenReturn(new int[]{3, 6, 0});



        ClusterOverviewDto result = clusterHealthViewService.getExternalClusterOverview(extCluster);



        assertNotNull(result);

        assertNotNull(result.getBrokers());

        assertEquals(1, result.getBrokers().size());

        assertEquals(1, result.getBrokers().get(0).getBrokerId());



        assertNotNull(result.getUptime());

        assertEquals(1, result.getUptime().getActiveController());

    }



    @Test

    void testNullOrEmptyExternalNodeListsAreHandled() {

        ExternalCluster extCluster = new ExternalCluster();

        extCluster.setId(UUID.randomUUID());



        when(externalClusterNodeRepository.findByClusterId(extCluster.getId())).thenReturn(Collections.emptyList());


        lenient().when(clusterValidationService.parseKafkaVersion(any())).thenReturn(new int[]{3, 6, 0});



        ClusterOverviewDto result = clusterHealthViewService.getExternalClusterOverview(extCluster);



        assertNotNull(result);

        assertTrue(result.getBrokers().isEmpty());

    }

}
