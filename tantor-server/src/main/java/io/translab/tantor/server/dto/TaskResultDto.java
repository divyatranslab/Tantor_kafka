package io.translab.tantor.server.dto;

import lombok.Data;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class TaskResultDto {
    private String taskId;
    private String hostId;
    private String status; // SUCCESS, FAILED
    private String logOutput;
    private String errorMsg;
    private String currentStep;
    private String failedReason;
}
