package io.translab.tantor.artifact.audit;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class ArtifactAuditControllerTest {

    @Test
    void recentBoundsPageAndSize() {
        ArtifactAuditService service = mock(ArtifactAuditService.class);
        ArtifactAuditLog log = new ArtifactAuditLog();
        log.setAction("UPLOAD");

        Page<ArtifactAuditLog> page = new PageImpl<>(List.of(log));
        when(service.recent(0, 50)).thenReturn(page);
        when(service.integrity()).thenReturn("NOT_ENABLED");

        ArtifactAuditController controller = new ArtifactAuditController(service);
        Map<String, Object> resp = controller.recent(0, 50);

        assertThat(resp.get("total")).isEqualTo(1L);
        assertThat(resp.get("events")).isNotNull();
        verify(service).recent(0, 50);
    }

    @Test
    void getByResourceBoundsPageAndSize() {
        ArtifactAuditService service = mock(ArtifactAuditService.class);
        Page<ArtifactAuditLog> page = new PageImpl<>(List.of());
        when(service.getLogsForResource("res-1", 0, 50)).thenReturn(page);
        when(service.integrity()).thenReturn("NOT_ENABLED");

        ArtifactAuditController controller = new ArtifactAuditController(service);
        Map<String, Object> resp = controller.getByResource("res-1", 0, 50);

        assertThat(resp.get("total")).isEqualTo(0L);
        verify(service).getLogsForResource("res-1", 0, 50);
    }
}
