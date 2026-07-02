package io.translab.tantor.server.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.translab.tantor.server.domain.AuditLog;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AuditServiceTest {

    @Test
    void createsHashChainedEventAndRedactsSecrets() {
        AuditLogRepository repository = mock(AuditLogRepository.class);
        when(repository.findFirstByOrderByCreatedAtDescIdDesc()).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(AuditLog.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AuditService service = new AuditService(repository, new ObjectMapper());

        service.recordAs("operator-1", "TEST", "10.0.0.8", "PERMISSION", "USER_ROLE_CHANGED",
                "USER", "user-7", null, "SUCCESS",
                Map.of("role", "monitor", "password", "never-store-this"),
                Map.of("role", "admin", "apiToken", "also-secret"), null, Map.of("reason", "approved"));

        var captor = org.mockito.ArgumentCaptor.forClass(AuditLog.class);
        verify(repository).saveAndFlush(captor.capture());
        AuditLog event = captor.getValue();
        assertThat(event.getRecordHash()).hasSize(64);
        assertThat(event.getPreviousHash()).isNull();
        assertThat(event.getActor()).isEqualTo("operator-1");
        assertThat(event.getOldValue()).contains("[REDACTED]").doesNotContain("never-store-this");
        assertThat(event.getNewValue()).contains("[REDACTED]").doesNotContain("also-secret");

        when(repository.findAllByOrderByCreatedAtAscIdAsc()).thenReturn(List.of(event));
        assertThat(service.verifyIntegrity()).isEqualTo("VERIFIED");
    }
}
