package io.translab.tantor.server.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class CentralLogEntryDto {
    private Instant timestamp;
    private String source;
    private String component;
    private String level;
    private String message;
    private String hostId;
    private UUID clusterId;
    private UUID jobId;
    private UUID taskId;
    private String status;
    private String correlationId;
}
