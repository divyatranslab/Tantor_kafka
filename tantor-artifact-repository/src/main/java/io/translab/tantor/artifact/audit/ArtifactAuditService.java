package io.translab.tantor.artifact.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
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
        repository.lockLedger();
        ArtifactAuditLog event = new ArtifactAuditLog();
        event.setActor(actor == null || actor.isBlank() ? "system" : actor);
        event.setCategory("PACKAGE");
        event.setAction(action);
        event.setResourceType("ARTIFACT");
        event.setResourceId(resourceId);
        event.setStatus(status);
        event.setOldValue(json(oldValue));
        event.setNewValue(json(newValue));
        event.setDetails(json(details));
        event.setIpAddress(ipAddress);
        event.setPreviousHash(repository.findFirstByOrderByCreatedAtDescIdDesc().map(ArtifactAuditLog::getRecordHash).orElse(null));
        event.setCreatedAt(OffsetDateTime.now());
        event.setRecordHash(hash(event));
        repository.saveAndFlush(event);
    }

    public Page<ArtifactAuditLog> recent(int page, int size) {
        return repository.findAllByOrderByCreatedAtDesc(PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 500)));
    }

    public String integrity() {
        String previous = null;
        for (ArtifactAuditLog event : repository.findAllByOrderByCreatedAtAscIdAsc()) {
            if (!java.util.Objects.equals(previous, event.getPreviousHash()) || !java.util.Objects.equals(hash(event), event.getRecordHash())) return "BROKEN";
            previous = event.getRecordHash();
        }
        return "VERIFIED";
    }

    private String json(Object value) {
        if (value == null) return null;
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception e) { return "{\"captureError\":true}"; }
    }

    private String hash(ArtifactAuditLog event) {
        String material = String.join("|", n(event.getPreviousHash()), n(event.getActor()), n(event.getCategory()),
                n(event.getAction()), n(event.getResourceType()), n(event.getResourceId()), n(event.getStatus()),
                n(event.getOldValue()), n(event.getNewValue()), n(event.getDetails()), n(event.getIpAddress()),
                event.getSource(), event.getCreatedAt().toString());
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException("Unable to hash artifact audit event", e); }
    }

    private String n(String value) { return value == null ? "" : value; }
}
