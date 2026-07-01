package io.translab.tantor.server.service;

import io.translab.tantor.server.domain.ActivityLog;
import io.translab.tantor.server.domain.Alert;
import io.translab.tantor.server.repository.ActivityLogRepository;
import io.translab.tantor.server.repository.AlertRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ActivityAlertService {

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
}
