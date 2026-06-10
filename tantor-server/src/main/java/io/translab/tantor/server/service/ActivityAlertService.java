package io.translab.tantor.server.service;

import io.translab.tantor.server.domain.ActivityLog;
import io.translab.tantor.server.domain.Alert;
import io.translab.tantor.server.repository.ActivityLogRepository;
import io.translab.tantor.server.repository.AlertRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ActivityAlertService {

    private final ActivityLogRepository activityLogRepository;
    private final AlertRepository alertRepository;

    public void logActivity(String level, String message, UUID clusterId) {
        ActivityLog activity = new ActivityLog();
        activity.setLevel(level);
        activity.setMessage(message);
        activity.setClusterId(clusterId);
        activityLogRepository.save(activity);
        log.info("ACTIVITY [{}]: {}", level, message);
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
