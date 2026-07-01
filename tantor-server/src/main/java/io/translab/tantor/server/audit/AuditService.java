package io.translab.tantor.server.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.translab.tantor.server.domain.AuditLog;
import jakarta.persistence.criteria.Predicate;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
public class AuditService {
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "password", "passwordhash", "token", "secret", "credential", "authorization", "privatekey");

    private final AuditLogRepository repository;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID record(String category, String action, String resourceType, String resourceId,
                       UUID clusterId, String status, Object oldValue, Object newValue,
                       Object approval, Object details) {
        return recordAs(null, "MANAGEMENT_SERVER", null, category, action, resourceType, resourceId,
                clusterId, status, oldValue, newValue, approval, details);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID recordAs(String actorOverride, String source, String ipOverride,
                         String category, String action, String resourceType, String resourceId,
                         UUID clusterId, String status, Object oldValue, Object newValue,
                         Object approval, Object details) {
        repository.lockLedger();
        AuditLog previous = repository.findFirstByOrderByCreatedAtDescIdDesc().orElse(null);
        AuditLog event = new AuditLog();
        event.setActor(actorOverride == null || actorOverride.isBlank() ? currentActor() : actorOverride);
        event.setSource(text(source, "MANAGEMENT_SERVER"));
        event.setCategory(text(category, "SYSTEM").toUpperCase(Locale.ROOT));
        event.setAction(text(action, "UNKNOWN").toUpperCase(Locale.ROOT));
        event.setResourceType(text(resourceType, "SYSTEM").toUpperCase(Locale.ROOT));
        event.setResourceId(resourceId);
        event.setClusterId(clusterId);
        event.setStatus(text(status, "SUCCESS").toUpperCase(Locale.ROOT));
        event.setOldValue(json(oldValue));
        event.setNewValue(json(newValue));
        event.setApproval(json(approval));
        event.setDetails(json(details));
        event.setIpAddress(ipOverride == null ? requestIp() : ipOverride);
        event.setRequestId(requestId());
        event.setPreviousHash(previous == null ? null : previous.getRecordHash());
        event.setCreatedAt(Instant.now());
        event.setRecordHash(hash(event));
        return repository.saveAndFlush(event).getId();
    }

    public Page<AuditLog> search(String category, String action, String status, String resourceType,
                                 String actor, String search, Instant from, Instant to, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 500);
        return repository.findAll((root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            equalIgnoreCase(predicates, cb, root.get("category"), category);
            equalIgnoreCase(predicates, cb, root.get("action"), action);
            equalIgnoreCase(predicates, cb, root.get("status"), status);
            equalIgnoreCase(predicates, cb, root.get("resourceType"), resourceType);
            if (actor != null && !actor.isBlank()) predicates.add(cb.equal(root.get("actor"), actor));
            if (from != null) predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            if (to != null) predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), to));
            if (search != null && !search.isBlank()) {
                String like = "%" + search.toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("resourceId")), like),
                        cb.like(cb.lower(root.get("actor")), like),
                        cb.like(cb.lower(root.get("action")), like),
                        cb.like(cb.lower(root.get("resourceType")), like)));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        }, PageRequest.of(Math.max(page, 0), safeSize, Sort.by(Sort.Direction.DESC, "createdAt", "id")));
    }

    public Map<String, Object> summary() {
        return Map.of(
                "total", repository.count(),
                "successful", repository.countByStatus("SUCCESS"),
                "failed", repository.countByStatus("FAILED"),
                "approvals", repository.countByCategory("APPROVAL"),
                "integrity", verifyIntegrity()
        );
    }

    public String verifyIntegrity() {
        String previousHash = null;
        for (AuditLog event : repository.findAllByOrderByCreatedAtAscIdAsc()) {
            if ("LEGACY".equals(event.getSource())) {
                previousHash = event.getRecordHash();
                continue;
            }
            if (!Objects.equals(previousHash, event.getPreviousHash()) || !Objects.equals(hash(event), event.getRecordHash())) {
                return "BROKEN";
            }
            previousHash = event.getRecordHash();
        }
        return "VERIFIED";
    }

    private void equalIgnoreCase(List<Predicate> predicates, jakarta.persistence.criteria.CriteriaBuilder cb,
                                 jakarta.persistence.criteria.Path<String> path, String value) {
        if (value != null && !value.isBlank()) predicates.add(cb.equal(cb.upper(path), value.toUpperCase(Locale.ROOT)));
    }

    private String json(Object value) {
        if (value == null) return null;
        try {
            JsonNode node = objectMapper.valueToTree(value);
            redact(node);
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            return "{\"captureError\":\"Unable to serialize audit value\"}";
        }
    }

    private void redact(JsonNode node) {
        if (node == null) return;
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            List<String> fields = new ArrayList<>();
            object.fieldNames().forEachRemaining(fields::add);
            for (String field : fields) {
                String normalized = field.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
                if (SENSITIVE_KEYS.stream().anyMatch(normalized::contains)) object.put(field, "[REDACTED]");
                else redact(object.get(field));
            }
        } else if (node.isArray()) {
            node.forEach(this::redact);
        }
    }

    private String hash(AuditLog event) {
        String material = String.join("|",
                nullable(event.getPreviousHash()), nullable(event.getActor()), nullable(event.getCategory()),
                nullable(event.getAction()), nullable(event.getResourceType()), nullable(event.getResourceId()),
                nullable(event.getStatus()), nullable(event.getOldValue()), nullable(event.getNewValue()),
                nullable(event.getApproval()), nullable(event.getDetails()), nullable(event.getIpAddress()),
                nullable(event.getSource()), nullable(event.getRequestId()), event.getCreatedAt().toString());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to hash audit event", e);
        }
    }

    private String currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null || "anonymousUser".equals(auth.getName())) return "system";
        return auth.getName();
    }

    private HttpServletRequest request() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs ? attrs.getRequest() : null;
    }

    private String requestIp() {
        HttpServletRequest request = request();
        if (request == null) return null;
        String forwarded = request.getHeader("X-Forwarded-For");
        return forwarded == null || forwarded.isBlank() ? request.getRemoteAddr() : forwarded.split(",")[0].trim();
    }

    private String requestId() {
        HttpServletRequest request = request();
        if (request == null) return UUID.randomUUID().toString();
        String supplied = request.getHeader("X-Request-ID");
        return supplied == null || supplied.isBlank() ? UUID.randomUUID().toString() : supplied.substring(0, Math.min(100, supplied.length()));
    }

    private String text(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
    private String nullable(String value) { return value == null ? "" : value; }
}
