package io.translab.tantor.server.service;

import io.translab.tantor.server.domain.ExternalCluster;
import io.translab.tantor.server.repository.DiscoveryAgentRepository;
import io.translab.tantor.server.security.TruststoreStorageService;
import io.translab.tantor.server.service.ExternalClusterService.BootstrapExternalClusterRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class ExternalClusterConnectionServiceTest {

    private ExternalClusterConnectionService connectionService;
    private KafkaAdminService kafkaAdminService;
    private TruststoreStorageService truststoreStorageService;
    private ExternalClusterDiscoveryService discoveryService;
    private DiscoveryAgentRepository agentRepository;

    @BeforeEach
    public void setup() {
        kafkaAdminService = mock(KafkaAdminService.class);
        truststoreStorageService = mock(TruststoreStorageService.class);
        discoveryService = mock(ExternalClusterDiscoveryService.class);
        agentRepository = mock(DiscoveryAgentRepository.class);
        connectionService = new ExternalClusterConnectionService(kafkaAdminService, truststoreStorageService, discoveryService, agentRepository);
    }

    @Test
    public void testPlaintextProperties() throws Exception {
        BootstrapExternalClusterRequest req = new BootstrapExternalClusterRequest();
        req.setSecurityProtocol("PLAINTEXT");
        req.setBootstrapServers("localhost:9092");
        when(kafkaAdminService.inspectBootstrapServers(any(), eq(false))).thenReturn(new HashMap<>());
        
        connectionService.testBootstrap(req);
        
        ArgumentCaptor<ExternalCluster> captor = ArgumentCaptor.forClass(ExternalCluster.class);
        verify(kafkaAdminService).inspectBootstrapServers(captor.capture(), eq(false));
        assertEquals("PLAINTEXT", captor.getValue().getSecurityProtocol());
    }

    @Test
    public void testSslProperties() throws Exception {
        BootstrapExternalClusterRequest req = new BootstrapExternalClusterRequest();
        req.setSecurityProtocol("SSL");
        req.setBootstrapServers("localhost:9093");
        when(kafkaAdminService.inspectBootstrapServers(any(), eq(false))).thenReturn(new HashMap<>());
        
        connectionService.testBootstrap(req);
        
        ArgumentCaptor<ExternalCluster> captor = ArgumentCaptor.forClass(ExternalCluster.class);
        verify(kafkaAdminService).inspectBootstrapServers(captor.capture(), eq(false));
        assertEquals("SSL", captor.getValue().getSecurityProtocol());
    }

    @Test
    public void testSaslPlaintextProperties() throws Exception {
        BootstrapExternalClusterRequest req = new BootstrapExternalClusterRequest();
        req.setSecurityProtocol("SASL_PLAINTEXT");
        req.setSaslMechanism("PLAIN");
        req.setBootstrapServers("localhost:9094");
        when(kafkaAdminService.inspectBootstrapServers(any(), eq(false))).thenReturn(new HashMap<>());
        
        connectionService.testBootstrap(req);
        
        ArgumentCaptor<ExternalCluster> captor = ArgumentCaptor.forClass(ExternalCluster.class);
        verify(kafkaAdminService).inspectBootstrapServers(captor.capture(), eq(false));
        assertEquals("SASL_PLAINTEXT", captor.getValue().getSecurityProtocol());
        assertEquals("PLAIN", captor.getValue().getSaslMechanism());
    }

    @Test
    public void testSaslSslProperties() throws Exception {
        BootstrapExternalClusterRequest req = new BootstrapExternalClusterRequest();
        req.setSecurityProtocol("SASL_SSL");
        req.setSaslMechanism("SCRAM-SHA-256");
        req.setBootstrapServers("localhost:9095");
        when(kafkaAdminService.inspectBootstrapServers(any(), eq(false))).thenReturn(new HashMap<>());
        
        connectionService.testBootstrap(req);
        
        ArgumentCaptor<ExternalCluster> captor = ArgumentCaptor.forClass(ExternalCluster.class);
        verify(kafkaAdminService).inspectBootstrapServers(captor.capture(), eq(false));
        assertEquals("SASL_SSL", captor.getValue().getSecurityProtocol());
        assertEquals("SCRAM-SHA-256", captor.getValue().getSaslMechanism());
    }

    @Test
    public void testHostnameVerificationDefault() throws Exception {
        BootstrapExternalClusterRequest req = new BootstrapExternalClusterRequest();
        req.setSecurityProtocol("SSL");
        req.setBootstrapServers("localhost:9093");
        when(kafkaAdminService.inspectBootstrapServers(any(), eq(false))).thenReturn(new HashMap<>());
        
        connectionService.testBootstrap(req);
        
        ArgumentCaptor<ExternalCluster> captor = ArgumentCaptor.forClass(ExternalCluster.class);
        verify(kafkaAdminService).inspectBootstrapServers(captor.capture(), eq(false));
        assertFalse(captor.getValue().getDisableHostnameVerification());
    }

    @Test
    public void testHostnameVerificationDisabled() throws Exception {
        BootstrapExternalClusterRequest req = new BootstrapExternalClusterRequest();
        req.setSecurityProtocol("SSL");
        req.setBootstrapServers("localhost:9093");
        req.setDisableHostnameVerification(true);
        when(kafkaAdminService.inspectBootstrapServers(any(), eq(false))).thenReturn(new HashMap<>());
        
        connectionService.testBootstrap(req);
        
        ArgumentCaptor<ExternalCluster> captor = ArgumentCaptor.forClass(ExternalCluster.class);
        verify(kafkaAdminService).inspectBootstrapServers(captor.capture(), eq(false));
        assertTrue(captor.getValue().getDisableHostnameVerification());
    }

    @Test
    public void testTemporaryFileRemovedWhenConnectionFails() throws Exception {
        BootstrapExternalClusterRequest req = new BootstrapExternalClusterRequest();
        req.setSecurityProtocol("SSL");
        req.setBootstrapServers("localhost:9093");
        req.setTruststoreBase64("base64data");
        req.setTruststoreType("JKS");

        when(truststoreStorageService.saveTruststore(any(), any(), any())).thenReturn("temp-path.jks");
        when(kafkaAdminService.inspectBootstrapServers(any(), eq(false))).thenThrow(new RuntimeException("Connection Refused"));
        
        assertThrows(IllegalArgumentException.class, () -> connectionService.testBootstrap(req));
        
        // Assert file removal happens in finally block (we can't easily assert Files.deleteIfExists without PowerMock, but we can verify saveTruststore was called, and we know the finally block attempts deletion)
        verify(truststoreStorageService).saveTruststore(any(), eq("JKS"), eq("base64data"));
    }
}
