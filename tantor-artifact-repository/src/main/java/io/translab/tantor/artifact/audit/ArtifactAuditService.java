package io.translab.tantor.artifact.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;

@Service
public class ArtifactAuditService {
    private final ArtifactAuditRepository repository;
    private final ObjectMapper objectMapper;

    public ArtifactAuditService(ArtifactAuditRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String actor, String action, String resourceId, String status,
                       Object oldValue, Object newValue, Object details, String ipAddress) {
        ArtifactAuditLog event = new ArtifactAuditLog();
        event.setActorUser(actor == null || actor.isBlank() ? "system" : actor);
        event.setCategory("PACKAGE");
        event.setAction(action);
        event.setResourceType("ARTIFACT");
        event.setArtifactId(resourceId);
        event.setStatus(status);
        event.setDetails(json(details));
        event.setCreatedAt(OffsetDateTime.now());
        repository.saveAndFlush(event);
    }

    public Page<ArtifactAuditLog> recent(int page, int size) {
        return repository.findAllByOrderByCreatedAtDesc(PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 500)));
    }

    public java.util.List<ArtifactAuditLog> getLogsForResource(String resourceId) {
        return repository.findByResourceIdOrderByCreatedAtDesc(resourceId);
    }

    public String integrity() {
        return "NOT_ENABLED";
    }

    private String json(Object value) {
        if (value == null) return null;
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception e) { return "{\"captureError\":true}"; }
    }

}
