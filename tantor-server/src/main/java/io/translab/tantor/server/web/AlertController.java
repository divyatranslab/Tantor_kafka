package io.translab.tantor.server.web;

import io.translab.tantor.server.domain.Alert;
import io.translab.tantor.server.domain.Cluster;
import io.translab.tantor.server.domain.Host;
import io.translab.tantor.server.domain.Task;
import io.translab.tantor.server.repository.AlertRepository;
import io.translab.tantor.server.repository.ClusterRepository;
import io.translab.tantor.server.repository.HostRepository;
import io.translab.tantor.server.repository.TaskRepository;
import io.translab.tantor.server.service.HostStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/ui/alerts")
@RequiredArgsConstructor
public class AlertController {

    private final AlertRepository alertRepository;
    private final ClusterRepository clusterRepository;
    private final HostRepository hostRepository;
    private final TaskRepository taskRepository;
    private final HostStatusService hostStatusService;

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getActiveAlerts() {
        List<Cluster> clusters = clusterRepository.findByStatusNot("DELETED");
        List<Host> hosts = hostRepository.findAll().stream()
                .filter(hostStatusService::isInfrastructureHost)
                .toList();
        List<Task> tasks = taskRepository.findAll();

        Map<String, Cluster> clusterById = clusters.stream()
                .collect(Collectors.toMap(cluster -> cluster.getId().toString(), cluster -> cluster, (a, b) -> a));
        Map<String, Host> hostById = hosts.stream()
                .collect(Collectors.toMap(Host::getId, host -> host, (a, b) -> a));

        List<Map<String, Object>> alerts = new ArrayList<>();
        alertRepository.findByStatusOrderByCreatedAtDesc("ACTIVE")
                .forEach(alert -> alerts.add(storedAlert(alert, clusterById)));

        hosts.forEach(host -> {
            String effectiveStatus = hostStatusService.effectiveStatus(host);
            if ("OFFLINE".equalsIgnoreCase(effectiveStatus)) {
                alerts.add(runtimeAlert(
                        "host-offline-" + host.getId(),
                        "CRITICAL",
                        "Host agent offline",
                        "No recent heartbeat from " + hostLabel(host) + ". Deployments and service control may not work until the agent reconnects.",
                        null,
                        null,
                        host.getId(),
                        hostIp(host),
                        host.getLastHeartbeat(),
                        null,
                        "host"
                ));
            }

            long diskPct = diskUsedPercent(host);
            if (diskPct >= 95) {
                alerts.add(runtimeAlert(
                        "host-disk-full-" + host.getId(),
                        "CRITICAL",
                        "Host storage full",
                        hostLabel(host) + " is at " + diskPct + "% disk usage. Kafka may fail to start with 'No space left on device'.",
                        null,
                        null,
                        host.getId(),
                        hostIp(host),
                        host.getLastHeartbeat(),
                        null,
                        "storage"
                ));
            } else if (diskPct >= 85) {
                alerts.add(runtimeAlert(
                        "host-disk-warning-" + host.getId(),
                        "WARNING",
                        "Host storage pressure",
                        hostLabel(host) + " is at " + diskPct + "% disk usage. Clean old artifacts/logs before Kafka reaches a hard failure.",
                        null,
                        null,
                        host.getId(),
                        hostIp(host),
                        host.getLastHeartbeat(),
                        null,
                        "storage"
                ));
            }
        });

        clusters.forEach(cluster -> {
            List<Host> assignedHosts = assignedHosts(cluster, hostById);
            if ("FAILED".equalsIgnoreCase(cluster.getStatus())) {
                Task latest = latestTask(tasks, cluster.getId());
                alerts.add(runtimeAlert(
                        "cluster-failed-" + cluster.getId(),
                        "CRITICAL",
                        "Cluster failed",
                        cluster.getName() + " is marked failed. " + taskReason(latest, "Review the latest deployment or upgrade task."),
                        cluster.getId(),
                        cluster.getName(),
                        latest == null ? null : latest.getHostId(),
                        latest == null ? null : hostIp(hostById.get(latest.getHostId())),
                        latest == null ? OffsetDateTime.now() : latest.getUpdatedAt(),
                        logExcerpt(latest),
                        "cluster"
                ));
            } else if ("DELETING".equalsIgnoreCase(cluster.getStatus())) {
                alerts.add(runtimeAlert(
                        "cluster-deleting-" + cluster.getId(),
                        "WARNING",
                        "Cluster cleanup in progress",
                        cluster.getName() + " is still deleting. If it remains here, check the cleanup task logs.",
                        cluster.getId(),
                        cluster.getName(),
                        null,
                        null,
                        OffsetDateTime.now(),
                        null,
                        "cluster"
                ));
            }

            assignedHosts.stream()
                    .filter(host -> "OFFLINE".equalsIgnoreCase(hostStatusService.effectiveStatus(host)))
                    .findFirst()
                    .ifPresent(host -> alerts.add(runtimeAlert(
                            "cluster-host-offline-" + cluster.getId() + "-" + host.getId(),
                            "CRITICAL",
                            "Cluster host offline",
                            cluster.getName() + " is assigned to " + hostLabel(host) + ", but the host agent is offline.",
                            cluster.getId(),
                            cluster.getName(),
                            host.getId(),
                            hostIp(host),
                            host.getLastHeartbeat(),
                            null,
                            "cluster"
                    )));

            assignedHosts.stream()
                    .filter(host -> diskUsedPercent(host) >= 95)
                    .findFirst()
                    .ifPresent(host -> alerts.add(runtimeAlert(
                            "cluster-disk-full-" + cluster.getId() + "-" + host.getId(),
                            "CRITICAL",
                            "Cluster host storage full",
                            cluster.getName() + " is on " + hostLabel(host) + " where disk usage is " + diskUsedPercent(host) + "%. Kafka can fail with 'No space left on device'.",
                            cluster.getId(),
                            cluster.getName(),
                            host.getId(),
                            hostIp(host),
                            host.getLastHeartbeat(),
                            null,
                            "storage"
                    )));

            if (!"EXTERNAL".equalsIgnoreCase(cluster.getMode())
                    && "SUCCESS".equalsIgnoreCase(cluster.getStatus())
                    && !assignedHosts.isEmpty()
                    && !clusterBrokerPortListening(cluster, assignedHosts)) {
                Host firstHost = assignedHosts.get(0);
                alerts.add(runtimeAlert(
                        "cluster-port-closed-" + cluster.getId(),
                        "CRITICAL",
                        "Cluster broker port closed",
                        cluster.getName() + " is marked active, but broker port " + brokerPort(cluster) + " is not reachable from the management server.",
                        cluster.getId(),
                        cluster.getName(),
                        firstHost.getId(),
                        hostIp(firstHost),
                        OffsetDateTime.now(),
                        null,
                        "cluster"
                ));
            }

            if ("EXTERNAL".equalsIgnoreCase(cluster.getMode()) && "FAILED".equalsIgnoreCase(cluster.getStatus())) {
                alerts.add(runtimeAlert(
                        "external-failed-" + cluster.getId(),
                        "CRITICAL",
                        "External cluster unreachable",
                        cluster.getName() + " is connected as external but bootstrap/discovery health is failed. Bootstrap: " + nullToDash(cluster.getBootstrapServers()),
                        cluster.getId(),
                        cluster.getName(),
                        null,
                        cluster.getBootstrapServers(),
                        OffsetDateTime.now(),
                        null,
                        "external"
                ));
            }
        });

        tasks.stream()
                .filter(task -> "FAILED".equalsIgnoreCase(task.getStatus()))
                .sorted(Comparator.comparing(Task::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(12)
                .forEach(task -> {
                    Cluster cluster = task.getClusterId() == null ? null : clusterById.get(task.getClusterId().toString());
                    alerts.add(runtimeAlert(
                            "task-failed-" + task.getId(),
                            "CRITICAL",
                            prettyCommand(task.getCommand()) + " failed",
                            taskReason(task, "A task failed and needs review."),
                            task.getClusterId(),
                            cluster == null ? null : cluster.getName(),
                            task.getHostId(),
                            hostIp(hostById.get(task.getHostId())),
                            task.getUpdatedAt(),
                            logExcerpt(task),
                            "task"
                    ));
                });

        List<Map<String, Object>> deduped = alerts.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(
                        alert -> String.valueOf(alert.get("id")),
                        alert -> alert,
                        (first, ignored) -> first,
                        LinkedHashMap::new
                ))
                .values()
                .stream()
                .sorted((a, b) -> compareCreatedAt(b.get("createdAt"), a.get("createdAt")))
                .limit(50)
                .toList();
        return ResponseEntity.ok(deduped);
    }

    private Map<String, Object> storedAlert(Alert alert, Map<String, Cluster> clusterById) {
        Cluster cluster = alert.getClusterId() == null ? null : clusterById.get(alert.getClusterId().toString());
        return runtimeAlert(
                alert.getId().toString(),
                alert.getSeverity(),
                alert.getTitle(),
                alert.getDescription(),
                alert.getClusterId(),
                cluster == null ? null : cluster.getName(),
                null,
                null,
                alert.getCreatedAt() == null ? null : alert.getCreatedAt().atOffset(OffsetDateTime.now().getOffset()),
                null,
                "stored"
        );
    }

    private Map<String, Object> runtimeAlert(
            String id,
            String severity,
            String title,
            String description,
            UUID clusterId,
            String clusterName,
            String hostId,
            String hostIp,
            OffsetDateTime createdAt,
            String errorLog,
            String source
    ) {
        Map<String, Object> alert = new LinkedHashMap<>();
        alert.put("id", id);
        alert.put("severity", severity == null ? "WARNING" : severity);
        alert.put("title", title);
        alert.put("description", description);
        alert.put("clusterId", clusterId);
        alert.put("clusterName", clusterName);
        alert.put("hostId", hostId);
        alert.put("hostIp", hostIp);
        alert.put("status", "ACTIVE");
        alert.put("createdAt", createdAt == null ? OffsetDateTime.now() : createdAt);
        alert.put("errorLog", errorLog);
        alert.put("source", source);
        return alert;
    }

    private List<Host> assignedHosts(Cluster cluster, Map<String, Host> hostById) {
        if (cluster.getServices() == null) {
            return List.of();
        }
        return cluster.getServices().stream()
                .map(service -> hostById.get(service.getHostId()))
                .filter(Objects::nonNull)
                .toList();
    }

    private Task latestTask(List<Task> tasks, UUID clusterId) {
        if (clusterId == null) {
            return null;
        }
        return tasks.stream()
                .filter(task -> clusterId.equals(task.getClusterId()))
                .sorted(Comparator.comparing(Task::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .findFirst()
                .orElse(null);
    }

    private String taskReason(Task task, String fallback) {
        if (task == null) {
            return fallback;
        }
        String error = task.getErrorMsg();
        if (error == null || error.isBlank()) {
            error = task.getLogOutput();
        }
        return error == null || error.isBlank() ? fallback : shortText(error, 220);
    }

    private String logExcerpt(Task task) {
        if (task == null) {
            return null;
        }
        String log = task.getErrorMsg();
        if (log == null || log.isBlank()) {
            log = task.getLogOutput();
        }
        return log == null || log.isBlank() ? null : shortText(log, 1200);
    }

    private String shortText(String value, int maxLength) {
        String compact = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return compact.length() <= maxLength ? compact : compact.substring(0, maxLength - 1) + "...";
    }

    private long diskUsedPercent(Host host) {
        if (host == null || host.getDiskTotalGb() == null || host.getDiskTotalGb() <= 0) {
            return 0;
        }
        long used = host.getDiskUsedGb() == null ? 0L : host.getDiskUsedGb();
        return Math.min(100, Math.round((used * 100.0) / host.getDiskTotalGb()));
    }

    private boolean clusterBrokerPortListening(Cluster cluster, List<Host> assignedHosts) {
        int port = brokerPort(cluster);
        for (Host host : assignedHosts) {
            String ip = hostIp(host);
            if (ip == null || ip.isBlank()) {
                continue;
            }
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(ip, port), 600);
                return true;
            } catch (Exception ignored) {
                // Try the next assigned host.
            }
        }
        return false;
    }

    private int brokerPort(Cluster cluster) {
        Integer bootstrapPort = firstBootstrapPort(cluster.getBootstrapServers());
        if (bootstrapPort != null) {
            return bootstrapPort;
        }
        Integer configPort = configInt(cluster.getConfigJson(), "listener_port");
        return configPort == null ? 9092 : configPort;
    }

    private Integer firstBootstrapPort(String bootstrapServers) {
        if (bootstrapServers == null || bootstrapServers.isBlank()) {
            return null;
        }
        String endpoint = bootstrapServers.split(",")[0].trim();
        int colon = endpoint.lastIndexOf(':');
        if (colon < 0 || colon == endpoint.length() - 1) {
            return null;
        }
        try {
            return Integer.parseInt(endpoint.substring(colon + 1));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Integer configInt(String configJson, String key) {
        if (configJson == null || configJson.isBlank()) {
            return null;
        }
        String quotedKey = "\"" + key + "\"";
        int keyIndex = configJson.indexOf(quotedKey);
        if (keyIndex < 0) {
            return null;
        }
        int colon = configJson.indexOf(':', keyIndex + quotedKey.length());
        if (colon < 0) {
            return null;
        }
        int start = colon + 1;
        while (start < configJson.length() && !Character.isDigit(configJson.charAt(start))) {
            start++;
        }
        int end = start;
        while (end < configJson.length() && Character.isDigit(configJson.charAt(end))) {
            end++;
        }
        if (start == end) {
            return null;
        }
        try {
            return Integer.parseInt(configJson.substring(start, end));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String hostLabel(Host host) {
        if (host == null) {
            return "unknown host";
        }
        return (host.getHostname() == null || host.getHostname().isBlank() ? host.getId() : host.getHostname())
                + " (" + host.getId() + ")";
    }

    private String hostIp(Host host) {
        if (host == null || host.getIpAddresses() == null || host.getIpAddresses().isBlank()) {
            return null;
        }
        String cleaned = host.getIpAddresses().replace("[", "").replace("]", "").replace("\"", "");
        for (String value : cleaned.split(",")) {
            String ip = value.trim();
            if (!ip.isBlank() && !"localhost".equalsIgnoreCase(ip) && !"127.0.0.1".equals(ip)) {
                return ip;
            }
        }
        return cleaned.split(",")[0].trim();
    }

    private String prettyCommand(String command) {
        if (command == null || command.isBlank()) {
            return "Task";
        }
        String[] words = command.toLowerCase().replace('_', ' ').split(" ");
        List<String> titled = new ArrayList<>();
        for (String word : words) {
            if (!word.isBlank()) {
                titled.add(word.substring(0, 1).toUpperCase() + word.substring(1));
            }
        }
        return String.join(" ", titled);
    }

    private int compareCreatedAt(Object left, Object right) {
        OffsetDateTime a = toOffsetDateTime(left);
        OffsetDateTime b = toOffsetDateTime(right);
        return Comparator.nullsLast(Comparator.<OffsetDateTime>naturalOrder()).compare(a, b);
    }

    private OffsetDateTime toOffsetDateTime(Object value) {
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime;
        }
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(String.valueOf(value));
        } catch (Exception ignored) {
            return null;
        }
    }

    private String nullToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
