package io.translab.tantor.server.dto;

import jakarta.validation.constraints.NotNull;

public class ReadConfigRequest {
    @NotNull(message = "nodeId is required")
    private Object nodeId;

    public Object getNodeId() {
        return nodeId;
    }

    public void setNodeId(Object nodeId) {
        this.nodeId = nodeId;
    }
}
