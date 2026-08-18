package io.translab.tantor.server.service;

import io.translab.tantor.server.domain.ActivityLog;
import io.translab.tantor.server.domain.Alert;
import io.translab.tantor.server.repository.ActivityLogRepository;
import io.translab.tantor.server.repository.AlertRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ActivityAlertService {

    static final String EXTERNAL_DEGRADED_TITLE = "External Cluster Degraded";
    static final String EXTERNAL_FAILED_TITLE = "External Cluster Failed";

    private final ActivityLogRepository activityLogRepository;
    private final AlertRepository alertRepository;

    public void logActivity(String level, String message, UUID clusterId) {
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
        String eventType = normalized.contains("config") || normalized.contains("properties") ? "CONFIGURATION"
                : normalized.contains("restart") ? "RESTART"
                : normalized.contains("deploy") || normalized.contains("install") || normalized.contains("upgrade") ? "DEPLOYMENT"
                : normalized.contains("host") || normalized.contains("agent") ? "HOST" : "CLUSTER";
        String action = normalized.contains("delete") ? "DELETE"
                : normalized.contains("update") ? "UPDATE"
                : normalized.contains("restart") ? "RESTART"
                : normalized.contains("connect") || normalized.contains("register") ? "REGISTER"
                : normalized.contains("deploy") || normalized.contains("install") ? "DEPLOY" : "EXECUTE";
        logAudit(level, eventType, action, message, "CLUSTER",
                clusterId == null ? null : clusterId.toString(), clusterId,
                null, null, "SUCCESS", null, null);
    }

    public void logAudit(
            String level, String eventType, String action, String message,
            String resourceType, String resourceId, UUID clusterId,
            String oldValue, String newValue, String eventStatus,
            String approvalStatus, String metadata
    ) {
        ActivityLog activity = new ActivityLog();
        activity.setLevel(level);
        activity.setMessage(message);
        activity.setClusterId(clusterId);
        activity.setEventType(eventType);
        activity.setAction(action);
        activity.setResourceType(resourceType);
        activity.setResourceId(resourceId);
        activity.setOldValue(oldValue);
        activity.setNewValue(newValue);
        activity.setEventStatus(eventStatus == null ? "SUCCESS" : eventStatus);
        activity.setApprovalStatus(approvalStatus);
        activity.setMetadata(metadata);
        applyRequestContext(activity);
        activityLogRepository.save(activity);
        log.info("AUDIT [{}] {} {} on {}:{} - {}", level, eventType, action, resourceType, resourceId, message);
    }

    private void applyRequestContext(ActivityLog activity) {
        activity.setActor("system");
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) return;
        HttpServletRequest request = attributes.getRequest();
        String forwarded = request.getHeader("X-Forwarded-For");
        activity.setIpAddress(forwarded == null || forwarded.isBlank()
                ? request.getRemoteAddr() : forwarded.split(",")[0].trim());
        String actor = request.getHeader("X-User");
        if (actor != null && !actor.isBlank()) activity.setActor(actor.trim());
    }

    public void createAlert(String severity, String title, String description, UUID clusterId) {
        Alert alert = new Alert();
        alert.setSeverity(severity);
        alert.setTitle(title);
        alert.setDescription(description);
        alert.setClusterId(clusterId);
        alert.setStatus("ACTIVE");
        alertRepository.save(alert);
        log.warn("ALERT [{}]: {} - {}", severity, title, description);
    }

    /**
     * Keeps the persisted external-cluster health alert in lockstep with the
     * latest scheduler result. The stable key prevents duplicate ACTIVE rows,
     * while resolved rows remain in the database as alert history.
     */
    @Transactional
    public void synchronizeExternalClusterHealth(
            UUID clusterId,
            String clusterName,
            String healthStatus) {
        if (clusterId == null) {
            return;
        }

        String normalizedStatus = healthStatus == null
                ? ""
                : healthStatus.trim().toUpperCase(Locale.ROOT);
        String activeKey = null;
        if ("DEGRADED".equals(normalizedStatus)) {
            activeKey = externalDegradedKey(clusterId);
            activateAlert(
                    activeKey,
                    "WARNING",
                    EXTERNAL_DEGRADED_TITLE,
                    "The Discovery Agent for external cluster '" + clusterName
                            + "' has stopped reporting, but Kafka is still reachable.",
                    clusterId);
        } else if ("FAILED".equals(normalizedStatus)) {
            // This key intentionally matches AlertController's runtime failure
            // key so one Kafka outage cannot create two ACTIVE alert rows.
            activeKey = externalFailedKey(clusterId);
            activateAlert(
                    activeKey,
                    "CRITICAL",
                    EXTERNAL_FAILED_TITLE,
                    "Kafka Admin API cannot reach external cluster '" + clusterName + "'.",
                    clusterId);
        }

        resolveSupersededExternalHealthAlerts(clusterId, activeKey);
    }

    /**
     * Resolves legacy external-health alerts whose cluster was deleted or is no
     * longer present in the external-cluster registry.
     */
    @Transactional
    public void resolveOrphanedExternalClusterHealthAlerts(Set<UUID> activeClusterIds) {
        Set<UUID> currentIds = activeClusterIds == null ? Set.of() : activeClusterIds;
        Instant resolvedAt = Instant.now();
        alertRepository.findByStatusAndTitleIn(
                        "ACTIVE",
                        List.of(EXTERNAL_DEGRADED_TITLE, EXTERNAL_FAILED_TITLE))
                .stream()
                .filter(alert -> alert.getClusterId() == null
                        || !currentIds.contains(alert.getClusterId()))
                .forEach(alert -> resolveAlert(alert, resolvedAt));
    }

    private void activateAlert(
            String alertKey,
            String severity,
            String title,
            String description,
            UUID clusterId) {
        Alert alert = alertRepository.findByAlertKey(alertKey).orElseGet(Alert::new);
        boolean newlyActive = alert.getId() == null || !"ACTIVE".equalsIgnoreCase(alert.getStatus());
        if (newlyActive) {
            alert.setCreatedAt(Instant.now());
        }
        alert.setAlertKey(alertKey);
        alert.setSeverity(severity);
        alert.setTitle(title);
        alert.setDescription(description);
        alert.setClusterId(clusterId);
        alert.setSource("external_health");
        alert.setStatus("ACTIVE");
        alert.setResolvedAt(null);
        alertRepository.save(alert);
        if (newlyActive) {
            log.warn("ALERT [{}]: {} - {}", severity, title, description);
        }
    }

    private void resolveSupersededExternalHealthAlerts(UUID clusterId, String activeKey) {
        List<Alert> activeHealthAlerts = alertRepository.findByClusterIdAndStatusAndTitleIn(
                clusterId,
                "ACTIVE",
                List.of(EXTERNAL_DEGRADED_TITLE, EXTERNAL_FAILED_TITLE));
        Instant resolvedAt = Instant.now();
        activeHealthAlerts.stream()
                .filter(alert -> activeKey == null || !activeKey.equals(alert.getAlertKey()))
                .forEach(alert -> resolveAlert(alert, resolvedAt));
    }

    private void resolveAlert(Alert alert, Instant resolvedAt) {
        alert.setStatus("RESOLVED");
        alert.setResolvedAt(resolvedAt);
        alertRepository.save(alert);
        log.info("Resolved external health alert {} for cluster {}",
                alert.getAlertKey() == null ? alert.getId() : alert.getAlertKey(),
                alert.getClusterId());
    }

    static String externalDegradedKey(UUID clusterId) {
        return "external-agent-degraded-" + clusterId;
    }

    static String externalFailedKey(UUID clusterId) {
        return "external-failed-" + clusterId;
    }
}
