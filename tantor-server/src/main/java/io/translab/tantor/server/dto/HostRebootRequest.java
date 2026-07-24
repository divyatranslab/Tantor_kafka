package io.translab.tantor.server.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

public class HostRebootRequest {
    @NotNull(message = "Explicit reboot confirmation is required.")
    @AssertTrue(message = "Explicit reboot confirmation is required.")
    private Boolean confirmed;

    public Boolean getConfirmed() {
        return confirmed;
    }

    public void setConfirmed(Boolean confirmed) {
        this.confirmed = confirmed;
    }
}
