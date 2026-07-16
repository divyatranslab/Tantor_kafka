package io.translab.tantor.server.web;

import io.translab.tantor.server.audit.AuditService;
import io.translab.tantor.server.dto.AclDTOs.AclCreateRequest;
import io.translab.tantor.server.dto.AclDTOs.AclCreateResponse;
import io.translab.tantor.server.service.SecurityOperationsService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SecurityControllerAuditTest {

    @Test
    void auditsSuccessfulAclCreation() {
        Fixture fixture = fixture();
        when(fixture.security.createAcl(fixture.clusterId, fixture.request))
                .thenReturn(new AclCreateResponse(1));

        fixture.controller.createAcl(fixture.clusterId, fixture.request);

        Map<String, Object> details = capturedDetails(fixture.audit, "SUCCESS");
        assertThat(details)
                .containsEntry("principal", "User:alice")
                .containsEntry("resourceType", "topic")
                .containsEntry("resourceName", "orders")
                .containsEntry("operation", "Read")
                .doesNotContainKey("error");
    }

    @Test
    void auditsFailedAclCreationAndRethrowsOriginalError() {
        Fixture fixture = fixture();
        RuntimeException failure = new RuntimeException(
                "Failed to create ACL: Kafka ACLs are disabled because no authorizer is configured on the cluster.");
        when(fixture.security.createAcl(fixture.clusterId, fixture.request)).thenThrow(failure);

        assertThatThrownBy(() -> fixture.controller.createAcl(fixture.clusterId, fixture.request))
                .isSameAs(failure);

        Map<String, Object> details = capturedDetails(fixture.audit, "FAILED");
        assertThat(details.get("error")).isEqualTo(failure.getMessage());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> capturedDetails(AuditService auditService, String status) {
        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(auditService).record(eq("SECURITY"), eq("ACL_CREATED"), eq("CLUSTER"), anyString(),
                any(UUID.class), eq(status), isNull(), isNull(), isNull(), details.capture());
        return details.getValue();
    }

    private Fixture fixture() {
        SecurityOperationsService security = mock(SecurityOperationsService.class);
        AuditService audit = mock(AuditService.class);
        SecurityController controller = new SecurityController(security, audit);
        UUID clusterId = UUID.randomUUID();
        AclCreateRequest request = new AclCreateRequest();
        request.setPrincipal("User:alice");
        request.setHost("*");
        request.setResource_type("topic");
        request.setResource_name("orders");
        request.setPattern_type("literal");
        request.setOperation("Read");
        request.setPermission_type("Allow");
        return new Fixture(controller, security, audit, clusterId, request);
    }

    private record Fixture(SecurityController controller, SecurityOperationsService security,
                           AuditService audit, UUID clusterId, AclCreateRequest request) {}
}
