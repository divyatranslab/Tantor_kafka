package io.translab.tantor.server.web;

import io.translab.tantor.server.domain.Cluster;
import io.translab.tantor.server.domain.Host;
import io.translab.tantor.server.domain.Task;
import io.translab.tantor.server.repository.ClusterRepository;
import io.translab.tantor.server.repository.HostRepository;
import io.translab.tantor.server.repository.TaskRepository;
import io.translab.tantor.server.service.HostStatusService;
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
    private final HostStatusService hostStatusService;

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getAllHosts() {
        List<Map<String, Object>> hosts = hostRepository.findAll().stream()
                .filter(hostStatusService::isInfrastructureHost)
                .map(this::hostSummary)
                .toList();
        return ResponseEntity.ok(hosts);
    }

    @PostMapping("/{id}/check-prerequisites")
    public ResponseEntity<?> checkPrerequisites(@PathVariable String id) {
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
        task.setParameters("{}");
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
            host.setStatus("UNAVAILABLE");
            hostRepository.save(host);
            return ResponseEntity.ok(hostSummary(host));
        }).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/mark-available")
    public ResponseEntity<?> markAvailable(@PathVariable String id) {
        return hostRepository.findById(id).map(host -> {
            host.setStatus("ONLINE");
            hostRepository.save(host);
            return ResponseEntity.ok(hostSummary(host));
        }).orElse(ResponseEntity.notFound().build());
    }
    @PostMapping("/{id}/approve")
    public ResponseEntity<Host> approveHost(@PathVariable String id) {
        return hostRepository.findById(id).map(host -> {
            host.setStatus("ONLINE");
            hostRepository.save(host);
            return ResponseEntity.ok(host);
        }).orElse(ResponseEntity.notFound().build());
    }

    @Transactional
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteHost(@PathVariable String id) {
        return hostRepository.findById(id).map(host -> {
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

            host.setClusterId(null);
            host.setStatus("PENDING");
            hostRepository.save(host);
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