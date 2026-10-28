package io.translab.tantor.server.dto;

import io.translab.tantor.server.domain.Job;
import io.translab.tantor.server.domain.JobStatus;
import io.translab.tantor.server.domain.JobType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JobSummaryDto {
    private UUID id;
    private JobType type;
    private JobStatus status;
    private String requestedBy;
    private String resourceKey;
    private Instant startTime;
    private Instant endTime;
    private String progress;
    private Integer retryCount;
    private Boolean rollbackSupported;
    private Instant createdAt;
    private Instant updatedAt;

    public static JobSummaryDto fromEntity(Job job) {
        if (job == null) {
            return null;
        }
        return JobSummaryDto.builder()
                .id(job.getId())
                .type(job.getType())
                .status(job.getStatus())
                .requestedBy(job.getRequestedBy())
                .resourceKey(job.getResourceKey())
                .startTime(job.getStartTime())
                .endTime(job.getEndTime())
                .progress(job.getProgress())
                .retryCount(job.getRetryCount())
                .rollbackSupported(job.getRollbackSupported())
                .createdAt(job.getCreatedAt())
                .updatedAt(job.getUpdatedAt())
                .build();
    }
}
