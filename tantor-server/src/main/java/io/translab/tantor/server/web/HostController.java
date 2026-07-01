package io.translab.tantor.server.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.translab.tantor.server.domain.Cluster;
import io.translab.tantor.server.domain.Host;
import io.translab.tantor.server.domain.Task;
import io.translab.tantor.server.domain.Job;
import io.translab.tantor.server.domain.JobStatus;
import io.translab.tantor.server.domain.JobStep;
import io.translab.tantor.server.domain.JobType;
import io.translab.tantor.server.repository.ClusterRepository;
import io.translab.tantor.server.repository.HostRepository;
import io.translab.tantor.server.repository.TaskRepository;
import io.translab.tantor.server.service.HostStatusService;
import io.translab.tantor.server.service.JobService;
import io.translab.tantor.server.audit.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ui/hosts")
@RequiredArgsConstructor
@Slf4j
public class HostController {

    private final HostRepository hostRepository;
    private final ClusterRepository clusterRepository;
    private final TaskRepository taskRepository;
    private final io.translab.tantor.server.service.ActivityAlertService activityAlertService;
    private final HostStatusService hostStatusService;
    private final ObjectMapper objectMapper;
    private final JobService jobService;
    private final AuditService auditService;

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getAllHosts() {
        List<Map<String, Object>> hosts = hostRepository.findAll().stream()
                .map(this::hostSummary)
                .toList();
        return ResponseEntity.ok(hosts);
    }

    @PostMapping("/{id}/check-prerequisites")
    public ResponseEntity<?> checkPrerequisites(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> options
    ) {
        Host host = hostRepository.findById(id).orElse(null);
        if (host == null) return ResponseEntity.notFound().build();
        String effectiveStatus = hostStatusService.effectiveStatus(host);
        if (!"ONLINE".equalsIgnoreCase(effectiveStatus)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "message",
                    "Host must be ONLINE before prerequisites can be checked. Current status: " + effectiveStatus
            ));
        }

        Task task = new Task();
        task.setHostId(id);
        task.setCommand("CHECK_PREREQUISITES");
        task.setStatus("PENDING");
        try {
            Map<String, Object> parameters = new LinkedHashMap<>();
            if (options != null) {
                Object mode = options.get("mode");
                Object requiredPorts = options.get("required_ports");
                if (mode != null) parameters.put("mode", String.valueOf(mode));
                if (requiredPorts != null) parameters.put("required_ports", String.valueOf(requiredPorts));
            }
            task.setParameters(objectMapper.writeValueAsString(parameters));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", "Invalid prerequisite options."));
        }
        taskRepository.save(task);
        return ResponseEntity.ok(Map.of("taskId", task.getId().toString()));
    }

    @GetMapping("/{id}/check-prerequisites/{taskId}")
    public ResponseEntity<?> prerequisiteResult(@PathVariable String id, @PathVariable UUID taskId) {
        return taskRepository.findById(taskId)
                .filter(task -> id.equals(task.getHostId()))
                .map(task -> ResponseEntity.ok(Map.of(
                        "taskId", task.getId().toString(),
                        "status", task.getStatus(),
                        "logOutput", task.getLogOutput() == null ? "" : task.getLogOutput(),
                        "errorMsg", task.getErrorMsg() == null ? "" : task.getErrorMsg()
                )))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/mark-unavailable")
    public ResponseEntity<?> markUnavailable(@PathVariable String id) {
        return hostRepository.findById(id).map(host -> {
            String previous = host.getStatus();
            host.setStatus("UNAVAILABLE");
            hostRepository.save(host);
            activityAlertService.logAudit("WARN", "HOST", "MARK_UNAVAILABLE", "Host marked unavailable", "HOST", id,
                    host.getClusterId(), "ONLINE", "UNAVAILABLE", "SUCCESS", null, null);
            auditService.record("HOST", "HOST_MARKED_UNAVAILABLE", "HOST", id, host.getClusterId(), "SUCCESS",
                    Map.of("status", String.valueOf(previous)), Map.of("status", "UNAVAILABLE"), null, null);
            return ResponseEntity.ok(hostSummary(host));
        }).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/mark-available")
    public ResponseEntity<?> markAvailable(@PathVariable String id) {
        return hostRepository.findById(id).map(host -> {
            String previous = host.getStatus();
            host.setStatus("ONLINE");
            hostRepository.save(host);
            activityAlertService.logAudit("INFO", "HOST", "MARK_AVAILABLE", "Host marked available", "HOST", id,
                    host.getClusterId(), "UNAVAILABLE", "ONLINE", "SUCCESS", null, null);
            auditService.record("HOST", "HOST_MARKED_AVAILABLE", "HOST", id, host.getClusterId(), "SUCCESS",
                    Map.of("status", String.valueOf(previous)), Map.of("status", "ONLINE"), null, null);
            return ResponseEntity.ok(hostSummary(host));
        }).orElse(ResponseEntity.notFound().build());
    }
    @PostMapping("/{id}/approve")
    public ResponseEntity<?> approveHost(@PathVariable String id) {
        Host host = hostRepository.findById(id).orElse(null);
        if (host == null) return ResponseEntity.notFound().build();
        if (!"PENDING".equalsIgnoreCase(host.getStatus())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "Host is already connected or unavailable."));
        }

        Job job = new Job();
        job.setType(JobType.ONBOARDING);
        job.setStatus(JobStatus.PENDING);
        job.setRollbackSupported(true);
        job.setResourceKey("host:" + id);
        JobStep step = new JobStep();
        step.setStepOrder(1);
        step.setName("Connect host " + host.getHostname());
        step.setTargetId(id);
        try {
            String payload = objectMapper.writeValueAsString(Map.of("hostId", id));
            job.setPayload(payload);
            step.setPayload(payload);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", "Unable to create onboarding job."));
        }
        Job saved = jobService.createJob(job, List.of(step));
        activityAlertService.logAudit("INFO", "APPROVAL", "APPROVE", "Host onboarding approved", "HOST", id,
                host.getClusterId(), "PENDING", "ONBOARDING_QUEUED", "SUCCESS", "APPROVED", "jobId=" + saved.getId());
        auditService.record("APPROVAL", "HOST_ONBOARDING_APPROVED", "HOST", id, host.getClusterId(), "SUCCESS",
                Map.of("status", host.getStatus()), Map.of("jobId", saved.getId(), "status", "ONBOARDING_REQUESTED"),
                Map.of("approved", true), Map.of("hostname", String.valueOf(host.getHostname())));
        return ResponseEntity.ok(Map.of("jobId", saved.getId().toString(), "status", saved.getStatus().name()));
    }

    @Transactional
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteHost(@PathVariable String id) {
        return hostRepository.findById(id).map(host -> {
            Map<String, Object> oldValue = Map.of("status", String.valueOf(host.getStatus()),
                    "clusterId", String.valueOf(host.getClusterId()), "hostname", String.valueOf(host.getHostname()));
            if (host.getClusterId() != null) {
                boolean assignedToActiveCluster = clusterRepository.findById(host.getClusterId())
                    .filter(cluster -> !"DELETED".equalsIgnoreCase(cluster.getStatus()))
                    .isPresent();
                if (assignedToActiveCluster) {
                    return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                        "message",
                        "This host is assigned to an active cluster. Delete or force-delete the cluster before disconnecting the host."
                    ));
                }
            }

            UUID previousClusterId = host.getClusterId();
            String previousStatus = host.getStatus();
            host.setClusterId(null);
            host.setStatus("PENDING");
            hostRepository.save(host);
            activityAlertService.logAudit("INFO", "HOST", "DISCONNECT", "Host disconnected from management", "HOST", id,
                    previousClusterId, previousStatus, "PENDING", "SUCCESS", null, null);
            auditService.record("HOST", "HOST_REMOVED", "HOST", id, null, "SUCCESS", oldValue,
                    Map.of("status", "PENDING", "clusterId", ""), null,
                    Map.of("operation", "disconnect", "recordRetained", true));
            return ResponseEntity.ok(Map.of(
                "message",
                "Host disconnected. It is now waiting in discovered nodes and can be connected again."
            ));
        }).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/check-port/{port}")
    public ResponseEntity<?> checkPort(@PathVariable String id, @PathVariable int port) {
        Host host = hostRepository.findById(id).orElse(null);
        if (host == null) return ResponseEntity.notFound().build();

        if (port < 1 || port > 65535) {
            return ResponseEntity.badRequest().body(Map.of("message", "Invalid port number"));
        }

        String targetIp = host.getHostname();
        if (host.getIpAddresses() != null && !host.getIpAddresses().isEmpty()) {
            String raw = host.getIpAddresses().replace("[", "").replace("]", "").replace("\"", "").trim();
            if (!raw.isEmpty()) {
                targetIp = raw.split(",")[0].trim();
            }
        }

        boolean portInUse;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(targetIp, port), 2000);
            portInUse = true;
        } catch (Exception e) {
            portInUse = false;
        }

        boolean isFree = !portInUse;
        String message = isFree
            ? "Port " + port + " is free on " + host.getHostname()
            : "Port " + port + " is already in use on " + host.getHostname();

        log.info("Port check: {}:{} -> {}", targetIp, port, isFree ? "FREE" : "IN_USE");

        return ResponseEntity.ok(Map.of(
            "free", isFree,
            "host", host.getHostname(),
            "ip", targetIp,
            "port", port,
            "message", message
        ));
    }

    private Map<String, Object> hostSummary(Host host) {
        String effectiveStatus = hostStatusService.effectiveStatus(host);
        host.setStatus(effectiveStatus);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", host.getId());
        summary.put("hostname", host.getHostname());
        summary.put("ipAddresses", host.getIpAddresses());
        summary.put("osDetails", host.getOsDetails());
        summary.put("agentVersion", host.getAgentVersion());
        boolean discoveryAgent = hostStatusService.isDiscoveryAgent(host);
        summary.put("agentType", discoveryAgent ? "KAFKA_DISCOVERY" : "HOST");
        summary.put("deployable", !discoveryAgent);
        summary.put("javaVersion", host.getJavaVersion());
        summary.put("status", effectiveStatus);
        summary.put("lastHeartbeat", host.getLastHeartbeat());
        summary.put("cpuUsagePct", host.getCpuUsagePct());
        summary.put("memTotalMb", host.getMemTotalMb());
        summary.put("memUsedMb", host.getMemUsedMb());
        summary.put("diskTotalGb", host.getDiskTotalGb());
        summary.put("diskUsedGb", host.getDiskUsedGb());

        Optional<Cluster> activeCluster = host.getClusterId() == null
                ? Optional.empty()
                : clusterRepository.findById(host.getClusterId()).filter(cluster -> !"DELETED".equalsIgnoreCase(cluster.getStatus()));
        summary.put("available", activeCluster.isEmpty());
        activeCluster.ifPresent(cluster -> {
            summary.put("clusterId", cluster.getId().toString());
            summary.put("clusterName", cluster.getName());
        });
        return summary;
    }
}
