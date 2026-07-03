package io.translab.tantor.server.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.translab.tantor.server.domain.Job;
import io.translab.tantor.server.domain.JobStep;
import io.translab.tantor.server.domain.Task;
import io.translab.tantor.server.dto.CentralLogEntryDto;
import io.translab.tantor.server.dto.CentralLogResponseDto;
import io.translab.tantor.server.repository.JobRepository;
import io.translab.tantor.server.repository.JobStepRepository;
import io.translab.tantor.server.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class LogManagementService {

    private static final int DEFAULT_RETENTION_DAYS = 30;
    private static final int DEFAULT_LIMIT = 500;
    private static final int MAX_LIMIT = 2_000;

    private final JobRepository jobRepository;
    private final JobStepRepository jobStepRepository;
    private final TaskRepository taskRepository;
    private final ObjectMapper objectMapper;

    public CentralLogResponseDto search(String query, String source, String component, String hostId,
                                        UUID jobId, UUID clusterId, Integer retentionDays, Integer limit) {
        int effectiveRetention = retentionDays == null ? DEFAULT_RETENTION_DAYS : Math.max(retentionDays, 0);
        int effectiveLimit = Math.min(Math.max(limit == null ? DEFAULT_LIMIT : limit, 1), MAX_LIMIT);
        Instant cutoff = effectiveRetention == 0 ? Instant.EPOCH : Instant.now().minusSeconds(effectiveRetention * 86_400L);

        List<CentralLogEntryDto> entries = collectLogs().stream()
                .filter(entry -> !entry.getTimestamp().isBefore(cutoff))
                .filter(entry -> matches(query, entry.getMessage(), entry.getComponent(), entry.getStatus(), entry.getCorrelationId()))
                .filter(entry -> matchesExact(source, entry.getSource()))
                .filter(entry -> matchesExact(component, entry.getComponent()))
                .filter(entry -> matchesExact(hostId, entry.getHostId()))
                .filter(entry -> jobId == null || jobId.equals(entry.getJobId()))
                .filter(entry -> clusterId == null || clusterId.equals(entry.getClusterId()))
                .sorted(Comparator.comparing(CentralLogEntryDto::getTimestamp).reversed())
                .limit(effectiveLimit)
                .toList();

        return CentralLogResponseDto.builder()
                .entries(entries)
                .total(entries.size())
                .limit(effectiveLimit)
                .retentionDays(effectiveRetention)
                .build();
    }

    public String export(String query, String source, String component, String hostId,
                         UUID jobId, UUID clusterId, Integer retentionDays, Integer limit) {
        return search(query, source, component, hostId, jobId, clusterId, retentionDays, limit)
                .getEntries()
                .stream()
                .sorted(Comparator.comparing(CentralLogEntryDto::getTimestamp))
                .map(this::formatLine)
                .collect(Collectors.joining(System.lineSeparator()));
    }

    private List<CentralLogEntryDto> collectLogs() {
        List<CentralLogEntryDto> entries = new ArrayList<>();
        jobRepository.findAllByOrderByCreatedAtDesc().forEach(job -> entries.addAll(jobEntries(job)));
        jobStepRepository.findAll().forEach(step -> entries.addAll(stepEntries(step)));
        taskRepository.findAllByOrderByUpdatedAtDesc().forEach(task -> entries.addAll(taskEntries(task)));
        return entries;
    }

    private List<CentralLogEntryDto> jobEntries(Job job) {
        List<CentralLogEntryDto> entries = new ArrayList<>();
        Instant timestamp = firstNonNull(job.getUpdatedAt(), job.getEndTime(), job.getStartTime(), job.getCreatedAt(), Instant.now());
        if (hasText(job.getLogs())) {
            splitLines(job.getLogs()).forEach(line -> entries.add(entry(timestamp, "JOB", job.getType().name(), inferLevel(line, job.getStatus().name()),
                    line, null, null, job.getId(), null, job.getStatus().name(), job.getId().toString())));
        } else {
            entries.add(entry(timestamp, "JOB", job.getType().name(), levelFromStatus(job.getStatus().name()),
                    "Job " + job.getType() + " is " + job.getStatus(), null, null, job.getId(), null,
                    job.getStatus().name(), job.getId().toString()));
        }
        return entries;
    }

    private List<CentralLogEntryDto> stepEntries(JobStep step) {
        List<CentralLogEntryDto> entries = new ArrayList<>();
        Instant timestamp = firstNonNull(step.getUpdatedAt(), step.getEndTime(), step.getStartTime(), step.getCreatedAt(), Instant.now());
        UUID jobId = step.getJob() == null ? null : step.getJob().getId();
        String component = "JOB_STEP/" + normalizeComponent(step.getName());
        String correlationId = jobId == null ? step.getId().toString() : jobId + ":" + step.getId();
        if (hasText(step.getLogs())) {
            splitLines(step.getLogs()).forEach(line -> entries.add(entry(timestamp, "JOB_STEP", component,
                    inferLevel(line, step.getStatus().name()), line, step.getTargetId(), null, jobId, step.getAgentTaskId(),
                    step.getStatus().name(), correlationId)));
        } else {
            entries.add(entry(timestamp, "JOB_STEP", component, levelFromStatus(step.getStatus().name()),
                    "Step " + step.getName() + " is " + step.getStatus(), step.getTargetId(), null, jobId,
                    step.getAgentTaskId(), step.getStatus().name(), correlationId));
        }
        return entries;
    }

    private List<CentralLogEntryDto> taskEntries(Task task) {
        List<CentralLogEntryDto> entries = new ArrayList<>();
        Instant timestamp = toInstant(firstNonNull(task.getUpdatedAt(), task.getCreatedAt(), OffsetDateTime.now()));
        String component = componentFromTask(task.getCommand());
        String correlationId = task.getId().toString();
        if (hasText(task.getLogOutput())) {
            splitLines(task.getLogOutput()).forEach(line -> entries.add(entry(timestamp, "AGENT", component, inferLevel(line, task.getStatus()),
                    line, task.getHostId(), task.getClusterId(), null, task.getId(), task.getStatus(), correlationId)));
        }
        parseStepLogs(task.getStepLogs()).forEach((step, logs) -> splitLines(logs).forEach(line -> entries.add(entry(timestamp,
                "AGENT", component + "/" + normalizeComponent(step), inferLevel(line, task.getStatus()), line,
                task.getHostId(), task.getClusterId(), null, task.getId(), task.getStatus(), correlationId))));
        if (hasText(task.getErrorMsg())) {
            entries.add(entry(timestamp, "AGENT", component, "ERROR", task.getErrorMsg(), task.getHostId(), task.getClusterId(),
                    null, task.getId(), task.getStatus(), correlationId));
        }
        if (hasText(task.getFailedReason()) && !task.getFailedReason().equals(task.getErrorMsg())) {
            entries.add(entry(timestamp, "AGENT", component, "ERROR", task.getFailedReason(), task.getHostId(), task.getClusterId(),
                    null, task.getId(), task.getStatus(), correlationId));
        }
        if (entries.isEmpty()) {
            entries.add(entry(timestamp, "AGENT", component, levelFromStatus(task.getStatus()),
                    "Task " + task.getCommand() + " is " + task.getStatus(), task.getHostId(), task.getClusterId(),
                    null, task.getId(), task.getStatus(), correlationId));
        }
        return entries;
    }

    private CentralLogEntryDto entry(Instant timestamp, String source, String component, String level, String message,
                                     String hostId, UUID clusterId, UUID jobId, UUID taskId, String status, String correlationId) {
        return CentralLogEntryDto.builder()
                .timestamp(timestamp)
                .source(source)
                .component(component)
                .level(level)
                .message(message)
                .hostId(hostId)
                .clusterId(clusterId)
                .jobId(jobId)
                .taskId(taskId)
                .status(status)
                .correlationId(correlationId)
                .build();
    }

    private List<String> splitLines(String logs) {
        return logs.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .limit(300)
                .toList();
    }

    private Map<String, String> parseStepLogs(String stepLogs) {
        if (!hasText(stepLogs)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(stepLogs, new TypeReference<LinkedHashMap<String, String>>() {});
        } catch (Exception ignored) {
            return Map.of("raw_step_logs", stepLogs);
        }
    }

    private boolean matches(String needle, String... values) {
        if (!hasText(needle)) {
            return true;
        }
        String normalizedNeedle = needle.toLowerCase(Locale.ROOT);
        for (String value : values) {
            if (value != null && value.toLowerCase(Locale.ROOT).contains(normalizedNeedle)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesExact(String expected, String actual) {
        return !hasText(expected) || expected.equalsIgnoreCase(actual);
    }

    private String componentFromTask(String command) {
        String normalized = normalizeComponent(command);
        if (normalized.contains("KAFKA") || normalized.contains("BROKER")) {
            return "BROKER";
        }
        if (normalized.contains("ZOOKEEPER") || normalized.contains("ZK")) {
            return "ZOOKEEPER";
        }
        if (normalized.contains("SCHEMA")) {
            return "SCHEMA_REGISTRY";
        }
        if (normalized.contains("CONNECT")) {
            return "CONNECT";
        }
        if (normalized.contains("KSQL")) {
            return "KSQLDB";
        }
        return normalized.isBlank() ? "AGENT_TASK" : normalized;
    }

    private String normalizeComponent(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_+|_+$", "").toUpperCase(Locale.ROOT);
    }

    private String inferLevel(String line, String status) {
        String normalized = line == null ? "" : line.toLowerCase(Locale.ROOT);
        if (normalized.contains("error") || normalized.contains("failed") || normalized.contains("exception")) {
            return "ERROR";
        }
        if (normalized.contains("warn")) {
            return "WARN";
        }
        return levelFromStatus(status);
    }

    private String levelFromStatus(String status) {
        if ("FAILED".equalsIgnoreCase(status) || "ERROR".equalsIgnoreCase(status)) {
            return "ERROR";
        }
        if ("CANCELLED".equalsIgnoreCase(status) || "ROLLBACK_DONE".equalsIgnoreCase(status)) {
            return "WARN";
        }
        return "INFO";
    }

    private String formatLine(CentralLogEntryDto entry) {
        return String.format("[%s] %-5s %-9s %-18s host=%s job=%s task=%s %s",
                entry.getTimestamp(), entry.getLevel(), entry.getSource(), entry.getComponent(),
                valueOrDash(entry.getHostId()), valueOrDash(entry.getJobId()), valueOrDash(entry.getTaskId()),
                entry.getMessage());
    }

    private String valueOrDash(Object value) {
        return value == null ? "-" : String.valueOf(value);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    @SafeVarargs
    private <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private Instant toInstant(OffsetDateTime dateTime) {
        return dateTime == null ? Instant.now() : dateTime.toInstant();
    }
}
