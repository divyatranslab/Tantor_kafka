package io.translab.tantor.server.service;

import io.translab.tantor.server.domain.ExternalCluster;
import io.translab.tantor.server.repository.ExternalClusterNodeRepository;
import io.translab.tantor.server.repository.ExternalClusterRepository;
import io.translab.tantor.server.service.ExternalClusterService.BootstrapExternalClusterRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doNothing;

public class ExternalClusterRegistrationServiceTest {

    private ExternalClusterRegistrationService registrationService;
    private ExternalClusterRepository clusterRepository;
    private ExternalClusterNodeRepository nodeRepository;
    private ExternalClusterConnectionService connectionService;

    @BeforeEach
    public void setup() {
        clusterRepository = mock(ExternalClusterRepository.class);
        nodeRepository = mock(ExternalClusterNodeRepository.class);
        connectionService = mock(ExternalClusterConnectionService.class);
        registrationService = new ExternalClusterRegistrationService(
            clusterRepository, nodeRepository, mock(io.translab.tantor.server.repository.DiscoveryAgentRepository.class),
            mock(io.translab.tantor.server.security.EncryptionService.class), mock(io.translab.tantor.server.security.TruststoreStorageService.class),
            mock(io.translab.tantor.server.audit.AuditService.class), mock(ActivityAlertService.class),
            connectionService, mock(ExternalClusterDiscoveryService.class),
            mock(ExternalClusterQueryService.class)
        );
    }

    @Test
    public void testConnectionAllowedWithNoAgent() {
        BootstrapExternalClusterRequest req = new BootstrapExternalClusterRequest();
        req.setBootstrapServers("test:9092");
        req.setBrokerCount(3);
        req.setName("TestCluster");
        req.setEnvironment("DEV");
        req.setSecurityProtocol("PLAINTEXT");
        req.setAgentFound(false);
        
        when(connectionService.testBootstrap(any())).thenReturn(java.util.Map.of("connected", true, "clusterId", "test-cluster"));
        
        ExternalCluster saved = new ExternalCluster();
        saved.setId(java.util.UUID.randomUUID());
        saved.setName("TestCluster");
        when(clusterRepository.save(any())).thenReturn(saved);
        
        ExternalCluster result = registrationService.registerBootstrapCluster(req);
        assertNotNull(result);
        assertEquals("TestCluster", result.getName());
    }

    @Test
    public void testConnectBlockedWhenNoSelectedNodeHasActiveAgent() {
        BootstrapExternalClusterRequest req = new BootstrapExternalClusterRequest();
        req.setBootstrapServers("test:9092");
        req.setAgentFound(true);
        // no selected agents!
        assertThrows(IllegalArgumentException.class, () -> registrationService.registerBootstrapCluster(req));
    }
}
