package io.translab.tantor.server.service;

import io.translab.tantor.server.service.ExternalClusterService.BootstrapExternalClusterRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import java.util.UUID;

import static org.mockito.Mockito.verify;

public class ExternalClusterServiceTest {
    private ExternalClusterService facade;
    private ExternalClusterTaskService taskService;
    private ExternalClusterDiscoveryService discoveryService;
    private ExternalClusterConnectionService connectionService;
    private ExternalClusterRegistrationService registrationService;
    private ExternalClusterHealthService healthService;
    
    @BeforeEach
    public void setup() {
        taskService = Mockito.mock(ExternalClusterTaskService.class);
        discoveryService = Mockito.mock(ExternalClusterDiscoveryService.class);
        connectionService = Mockito.mock(ExternalClusterConnectionService.class);
        registrationService = Mockito.mock(ExternalClusterRegistrationService.class);
        healthService = Mockito.mock(ExternalClusterHealthService.class);
        facade = new ExternalClusterService(taskService, discoveryService, connectionService, registrationService, healthService);
    }

    @Test
    public void testRegisterBootstrapClusterDelegate() {
        BootstrapExternalClusterRequest req = new BootstrapExternalClusterRequest();
        facade.registerBootstrapCluster(req);
        verify(registrationService).registerBootstrapCluster(req);
    }
    
    @Test
    public void testCheckExternalClustersHealthDelegate() {
        facade.checkExternalClustersHealth();
        verify(healthService).checkExternalClustersHealth();
    }
    
    @Test
    public void testDeleteExternalClusterDelegate() {
        UUID id = UUID.randomUUID();
        facade.deleteExternalCluster(id);
        verify(registrationService).deleteExternalCluster(id);
    }
}
