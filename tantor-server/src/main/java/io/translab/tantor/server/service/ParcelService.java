package io.translab.tantor.server.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.translab.tantor.server.domain.Host;
import io.translab.tantor.server.domain.HostParcel;
import io.translab.tantor.server.domain.Task;
import io.translab.tantor.server.repository.HostParcelRepository;
import io.translab.tantor.server.repository.HostRepository;
import io.translab.tantor.server.repository.TaskRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ParcelService {
    private static final String DEFAULT_PARCEL_DIR = "/srv/apps/tantor/parcels";

    private final HostParcelRepository hostParcelRepository;
    private final HostRepository hostRepository;
    private final TaskRepository taskRepository;
    private final ObjectMapper objectMapper;
    private final HostStatusService hostStatusService;

    @Value("${tantor.artifact-repo.url:http://localhost:8081}")
    private String artifactRepoUrl;

    public List<HostParcel> listStates() {
        return hostParcelRepository.findAll();
    }

    @Transactional
    public List<HostParcel> distribute(UUID artifactId, ParcelActionRequest request) {
        return scheduleAction("DISTRIBUTE_PARCEL", artifactId, request);
    }

    @Transactional
    public List<HostParcel> activate(UUID artifactId, ParcelActionRequest request) {
        return scheduleAction("ACTIVATE_PARCEL", artifactId, request);
    }

    @Transactional
    public List<HostParcel> deactivate(UUID artifactId, ParcelActionRequest request) {
        return scheduleAction("DEACTIVATE_PARCEL", artifactId, request);
    }

    @Transactional
    public List<HostParcel> remove(UUID artifactId, ParcelActionRequest request) {
        return scheduleAction("REMOVE_PARCEL", artifactId, request);
    }

    @Transactional
    public void processTaskResult(Task task) {
        if (!isParcelCommand(task.getCommand())) {
            return;
        }

        hostParcelRepository.findByLastTaskId(task.getId()).ifPresent(parcel -> {
            if ("FAILED".equalsIgnoreCase(task.getStatus())) {
                parcel.setStatus("FAILED");
                parcel.setErrorMsg(task.getErrorMsg());
                hostParcelRepository.save(parcel);
                return;
            }

            if ("RUNNING".equalsIgnoreCase(task.getStatus()) || "IN_PROGRESS".equalsIgnoreCase(task.getStatus())) {
                parcel.setStatus(inProgressStatus(task.getCommand()));
                parcel.setErrorMsg(null);
                hostParcelRepository.save(parcel);
                return;
            }

            if (!"SUCCESS".equalsIgnoreCase(task.getStatus())) {
                return;
            }

            switch (task.getCommand()) {
                case "DISTRIBUTE_PARCEL" -> {
                    parcel.setStatus("DISTRIBUTED");
                    parcel.setActive(false);
                }
                case "ACTIVATE_PARCEL" -> {
                    deactivateOtherActiveParcels(parcel);
                    parcel.setStatus("ACTIVE");
                    parcel.setActive(true);
                }
                case "DEACTIVATE_PARCEL" -> {
                    parcel.setStatus("DEACTIVATED");
                    parcel.setActive(false);
                }
                case "REMOVE_PARCEL" -> {
                    parcel.setStatus("REMOVED");
                    parcel.setActive(false);
                }
                default -> {
                    return;
                }
            }
            parcel.setErrorMsg(null);
            hostParcelRepository.save(parcel);
        });
    }

    private List<HostParcel> scheduleAction(String command, UUID artifactId, ParcelActionRequest request) {
        validateRequest(command, request);

        List<HostParcel> scheduled = new ArrayList<>();
        for (String hostId : request.getHostIds()) {
            Host host = hostRepository.findById(hostId)
                    .orElseThrow(() -> new IllegalArgumentException("Host " + hostId + " was not found."));
            String effectiveStatus = hostStatusService.effectiveStatus(host);
            if (!"ONLINE".equalsIgnoreCase(effectiveStatus)) {
                throw new IllegalArgumentException("Host " + hostId + " is not online. Current status: " + effectiveStatus + ".");
            }

            HostParcel parcel = hostParcelRepository.findByHostIdAndArtifactId(hostId, artifactId)
                    .orElseGet(HostParcel::new);
            boolean isNew = parcel.getId() == null;

            if (!isNew && !canRun(command, parcel)) {
                throw new IllegalArgumentException("Parcel " + request.getVersion() + " on host " + hostId + " is in state " + parcel.getStatus() + " and cannot run " + command + ".");
            }
            if (isNew && !"DISTRIBUTE_PARCEL".equals(command)) {
                throw new IllegalArgumentException("Parcel must be distributed to host " + hostId + " before it can be activated.");
            }

            parcel.setHostId(hostId);
            parcel.setArtifactId(artifactId);
            parcel.setServiceType(defaultString(request.getServiceType(), "KAFKA"));
            parcel.setVersion(required(request.getVersion(), "version"));
            parcel.setFileName(request.getFileName());
            parcel.setArtifactUrl(resolveAgentArtifactUrl(request.getArtifactUrl(), artifactId));
            parcel.setChecksum(request.getChecksum());
            parcel.setParcelDir(resolveParcelDir(command, request, parcel));
            parcel.setStatus(inProgressStatus(command));
            parcel.setErrorMsg(null);
            if ("REMOVE_PARCEL".equals(command) || "DEACTIVATE_PARCEL".equals(command)) {
                parcel.setActive(false);
            }

            Task task = createTask(command, hostId, parcel);
            taskRepository.save(task);
            parcel.setLastTaskId(task.getId());
            scheduled.add(hostParcelRepository.save(parcel));
        }
        return scheduled;
    }

    private Task createTask(String command, String hostId, HostParcel parcel) {
        Task task = new Task();
        task.setHostId(hostId);
        task.setCommand(command);
        task.setStatus("PENDING");
        if ("DISTRIBUTE_PARCEL".equals(command)) {
            task.setArtifactUrl(parcel.getArtifactUrl());
            task.setChecksum(parcel.getChecksum());
        }

        Map<String, Object> params = new HashMap<>();
        params.put("artifact_id", parcel.getArtifactId().toString());
        params.put("service_type", parcel.getServiceType());
        params.put("version", parcel.getVersion());
        params.put("file_name", parcel.getFileName());
        params.put("parcel_dir", parcel.getParcelDir());
        params.put("checksum", parcel.getChecksum());
        try {
            task.setParameters(objectMapper.writeValueAsString(params));
        } catch (JsonProcessingException e) {
            task.setParameters("{}");
        }
        return task;
    }

    private void validateRequest(String command, ParcelActionRequest request) {
        if (request == null || request.getHostIds() == null || request.getHostIds().isEmpty()) {
            throw new IllegalArgumentException("At least one host is required.");
        }
        if ("DISTRIBUTE_PARCEL".equals(command) && (request.getArtifactUrl() == null || request.getArtifactUrl().isBlank())) {
            throw new IllegalArgumentException("Artifact URL is required for distribution.");
        }
        required(request.getVersion(), "version");
    }

    private boolean canRun(String command, HostParcel parcel) {
        String status = parcel.getStatus() == null ? "" : parcel.getStatus();
        return switch (command) {
            case "DISTRIBUTE_PARCEL" -> Set.of("FAILED", "REMOVED").contains(status);
            case "ACTIVATE_PARCEL" -> Set.of("DISTRIBUTED", "DEACTIVATED", "ACTIVE").contains(status);
            case "DEACTIVATE_PARCEL" -> "ACTIVE".equals(status);
            case "REMOVE_PARCEL" -> Set.of("DISTRIBUTED", "DEACTIVATED", "FAILED", "REMOVED").contains(status);
            default -> false;
        };
    }

    private String inProgressStatus(String command) {
        return switch (command) {
            case "DISTRIBUTE_PARCEL" -> "DISTRIBUTING";
            case "ACTIVATE_PARCEL" -> "ACTIVATING";
            case "DEACTIVATE_PARCEL" -> "DEACTIVATING";
            case "REMOVE_PARCEL" -> "REMOVING";
            default -> "PENDING";
        };
    }

    private String resolveParcelDir(String command, ParcelActionRequest request, HostParcel parcel) {
        String requestedDir = request == null ? null : request.getParcelDir();
        if ("DISTRIBUTE_PARCEL".equals(command)) {
            return defaultString(requestedDir, DEFAULT_PARCEL_DIR);
        }
        return defaultString(requestedDir, defaultString(parcel.getParcelDir(), DEFAULT_PARCEL_DIR));
    }

    private boolean isParcelCommand(String command) {
        return command != null && command.endsWith("_PARCEL");
    }

    private void deactivateOtherActiveParcels(HostParcel activatedParcel) {
        for (HostParcel other : hostParcelRepository.findByHostIdAndServiceTypeAndActiveTrue(activatedParcel.getHostId(), activatedParcel.getServiceType())) {
            if (!Objects.equals(other.getId(), activatedParcel.getId())) {
                other.setActive(false);
                if ("ACTIVE".equals(other.getStatus())) {
                    other.setStatus("DEACTIVATED");
                }
                hostParcelRepository.save(other);
            }
        }
    }

    private String resolveAgentArtifactUrl(String artifactUrl, UUID artifactId) {
        String candidate = artifactUrl == null || artifactUrl.isBlank()
                ? "/api/v1/artifacts/" + artifactId + "/download"
                : artifactUrl.trim();
        try {
            URI uri = URI.create(candidate);
            if (!uri.isAbsolute()) {
                return joinArtifactRepoBase(candidate);
            }
            String rawPath = uri.getRawPath();
            if (rawPath != null && rawPath.startsWith("/api/v1/artifacts/")) {
                return joinArtifactRepoBase(pathAndQuery(uri));
            }
            if (isLoopbackHost(uri.getHost())) {
                return joinArtifactRepoBase(pathAndQuery(uri));
            }
        } catch (IllegalArgumentException ignored) {
            return candidate;
        }
        return candidate;
    }

    private String joinArtifactRepoBase(String pathAndQuery) {
        String base = artifactRepoUrl == null || artifactRepoUrl.isBlank()
                ? "http://localhost:8081"
                : artifactRepoUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String normalizedPath = pathAndQuery.startsWith("/") ? pathAndQuery : "/" + pathAndQuery;
        return base + normalizedPath;
    }

    private String pathAndQuery(URI uri) {
        String rawPath = uri.getRawPath() != null ? uri.getRawPath() : "";
        return uri.getRawQuery() == null ? rawPath : rawPath + "?" + uri.getRawQuery();
    }

    private boolean isLoopbackHost(String host) {
        return host != null && ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host));
    }

    private String required(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required.");
        }
        return value.trim();
    }

    private String defaultString(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    @Data
    public static class ParcelActionRequest {
        private List<String> hostIds;
        private String artifactUrl;
        private String checksum;
        private String serviceType;
        private String version;
        private String fileName;
        private String parcelDir;
    }
}
