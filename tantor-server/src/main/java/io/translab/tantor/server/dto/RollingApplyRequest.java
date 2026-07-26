package io.translab.tantor.server.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.Map;

public class RollingApplyRequest {
    private Boolean rollingRestart;

    @NotEmpty(message = "Changes cannot be empty")
    private List<Map<String, Object>> changes;

    public Boolean getRollingRestart() {
        return rollingRestart;
    }

    public void setRollingRestart(Boolean rollingRestart) {
        this.rollingRestart = rollingRestart;
    }

    public List<Map<String, Object>> getChanges() {
        return changes;
    }

    public void setChanges(List<Map<String, Object>> changes) {
        this.changes = changes;
    }
}
