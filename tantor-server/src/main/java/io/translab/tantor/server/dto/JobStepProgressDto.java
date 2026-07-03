package io.translab.tantor.server.dto;

import io.translab.tantor.server.domain.JobStep;
import io.translab.tantor.server.domain.JobStepStatus;
import io.translab.tantor.server.domain.Task;

import java.time.Instant;
import java.util.UUID;

public record JobStepProgressDto(
        UUID id,
        Integer stepOrder,
        String name,
        String targetId,
        JobStepStatus status,
        UUID agentTaskId,
        String logs,
        Integer retryCount,
        Instant startTime,
        Instant endTime,
        String agentTaskStatus,
        String currentStep,
        String stepLogs,
        String taskLogOutput,
        String taskErrorMsg,
        String failedReason
) {
    public static JobStepProgressDto from(JobStep step, Task task) {
        return new JobStepProgressDto(
                step.getId(),
                step.getStepOrder(),
                step.getName(),
                step.getTargetId(),
                step.getStatus(),
                step.getAgentTaskId(),
                step.getLogs(),
                step.getRetryCount(),
                step.getStartTime(),
                step.getEndTime(),
                task == null ? null : task.getStatus(),
                task == null ? null : task.getCurrentStep(),
                task == null ? null : task.getStepLogs(),
                task == null ? null : task.getLogOutput(),
                task == null ? null : task.getErrorMsg(),
                task == null ? null : task.getFailedReason()
        );
    }
}