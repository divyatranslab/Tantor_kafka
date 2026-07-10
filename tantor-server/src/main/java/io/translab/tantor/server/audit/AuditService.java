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
        AuditLog event = new AuditLog();
        event.setUserName(actorOverride == null || actorOverride.isBlank() ? currentActor() : actorOverride);
        event.setOrigin(text(source, "MANAGEMENT_SERVER"));
        event.setCategory(text(category, "SYSTEM").toUpperCase(Locale.ROOT));
        event.setAction(text(action, "UNKNOWN").toUpperCase(Locale.ROOT));
        event.setEvent(event.getAction());
        event.setResourceType(text(resourceType, "SYSTEM").toUpperCase(Locale.ROOT));
        event.setResourceId(resourceId);
        event.setResource(resourceId);
        event.setClusterId(clusterId);
        event.setStatus(text(status, "SUCCESS").toUpperCase(Locale.ROOT));
        event.setApproval(json(approval));
        event.setDetails(json(details));
        event.setIpAddress(ipOverride == null ? requestIp() : ipOverride);
        event.setRequestId(requestId());
        event.setCreatedTime(Instant.now());
        event.setCreatedBy(event.getUserName());
        event.setUserId(event.getUserName());

        if ("ARTIFACT".equalsIgnoreCase(event.getResourceType()) && event.getResourceId() != null) {
            try {
                event.setArtifactId(UUID.fromString(event.getResourceId()));
                java.util.List<Object[]> hostInfo = repository.findArtifactHostInfo(event.getResourceId());
                if (hostInfo != null && !hostInfo.isEmpty()) {
                    Object[] row = hostInfo.get(0);
                    if (row[0] != null) event.setHostIp(row[0].toString());
                    if (row[1] != null) event.setHostName(row[1].toString());
                }
            } catch (Exception e) {
                // Ignore invalid UUID or DB errors
            }
        } else if ("HOST".equalsIgnoreCase(event.getResourceType()) && event.getResourceId() != null) {
            event.setHostId(event.getResourceId());
            try {
                java.util.List<Object[]> hostInfo = repository.findHostInfo(event.getResourceId());
                if (hostInfo != null && !hostInfo.isEmpty()) {
                    Object[] row = hostInfo.get(0);
                    if (row[0] != null) event.setHostIp(row[0].toString());
                    if (row[1] != null) {
                        event.setHostName(row[1].toString());
                        event.setResource(row[1].toString());
                    }
                }
            } catch (Exception e) {
                // Ignore lookup failures; audit recording must not block the action.
            }
        } else if ("CLUSTER".equalsIgnoreCase(event.getResourceType())) {
            UUID lookupClusterId = clusterId;
            if (lookupClusterId == null && event.getResourceId() != null) {
                try {
                    lookupClusterId = UUID.fromString(event.getResourceId());
                    event.setClusterId(lookupClusterId);
                } catch (Exception ignored) {
                    // Keep the raw resource id if it is not a UUID.
                }
            }
            if (lookupClusterId != null) {
                try {
                    java.util.List<Object[]> clusterInfo = repository.findClusterInfo(lookupClusterId.toString());
                    if (clusterInfo != null && !clusterInfo.isEmpty()) {
                        Object[] row = clusterInfo.get(0);
                        if (row[0] != null) event.setResource(row[0].toString());
                        if (row[1] != null && event.getHostIp() == null) {
                            event.setHostIp(firstHost(row[1].toString()));
                        }
                    }
                } catch (Exception e) {
                    // Ignore lookup failures; audit recording must not block the action.
                }
            }
        }

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
            if (actor != null && !actor.isBlank()) predicates.add(cb.equal(root.get("userName"), actor));
            if (from != null) predicates.add(cb.greaterThanOrEqualTo(root.get("createdTime"), from));
            if (to != null) predicates.add(cb.lessThanOrEqualTo(root.get("createdTime"), to));
            if (search != null && !search.isBlank()) {
                String like = "%" + search.toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("resourceId")), like),
                        cb.like(cb.lower(root.get("resource")), like),
                        cb.like(cb.lower(root.get("hostName")), like),
                        cb.like(cb.lower(root.get("hostIp")), like),
                        cb.like(cb.lower(root.get("userName")), like),
                        cb.like(cb.lower(root.get("action")), like),
                        cb.like(cb.lower(root.get("resourceType")), like)));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        }, PageRequest.of(Math.max(page, 0), safeSize, Sort.by(Sort.Direction.DESC, "createdTime", "id")));
    }

    public Map<String, Object> summary() {
        return Map.of(
                "total", repository.count(),
                "successful", repository.countByStatus("SUCCESS"),
                "failed", repository.countByStatus("FAILED"),
                "approvals", repository.countByCategory("APPROVAL"),
                "integrity", "NOT_ENABLED"
        );
    }

    public String verifyIntegrity() {
        return "NOT_ENABLED";
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

    public String currentActor() {
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

    private String firstHost(String bootstrapServers) {
        if (bootstrapServers == null || bootstrapServers.isBlank()) return null;
        String first = bootstrapServers.split(",")[0].trim();
        int colon = first.indexOf(':');
        return colon > 0 ? first.substring(0, colon) : first;
    }
}
