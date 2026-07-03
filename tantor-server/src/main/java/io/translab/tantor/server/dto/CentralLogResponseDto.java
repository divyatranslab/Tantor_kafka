package io.translab.tantor.server.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class CentralLogResponseDto {
    private List<CentralLogEntryDto> entries;
    private int total;
    private int limit;
    private int retentionDays;
}
