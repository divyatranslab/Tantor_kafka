package io.translab.tantor.server.service;

import io.translab.tantor.server.domain.ExternalClusterNode;
import io.translab.tantor.server.domain.ExternalCluster;
import io.translab.tantor.server.service.ExternalClusterService.ExternalDiscoveryReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ExternalClusterDiscoveryServiceTest {

    private ExternalClusterDiscoveryService discoveryService;

    @BeforeEach
    public void setup() {
        discoveryService = new ExternalClusterDiscoveryService(null, null, null, null, null);
    }

    @Test
    public void testDiscoveryCandidatesAndMatching() {
        ExternalDiscoveryReport report = new ExternalDiscoveryReport();
        report.setNodeId(1);
        report.setHostname("node1");
        
        ExternalClusterNode node = new ExternalClusterNode();
        node.setNodeId(1);
        node.setHost("node1");
        
        boolean matches = discoveryService.matchesDiscoveryNode(node, report, null);
        assertTrue(matches, "Should match on nodeId");
        
        node.setNodeId(2);
        boolean matchesHost = discoveryService.matchesDiscoveryNode(node, report, null);
        assertTrue(matchesHost, "Should match on host fallback");
    }
}
