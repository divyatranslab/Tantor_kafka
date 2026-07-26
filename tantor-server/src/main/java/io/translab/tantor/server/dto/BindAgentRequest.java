package io.translab.tantor.server.dto;

import jakarta.validation.constraints.NotBlank;

public class BindAgentRequest {
    @NotBlank(message = "Agent ID required")
    private String agentId;

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }
}
