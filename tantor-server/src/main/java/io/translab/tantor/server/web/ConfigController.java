package io.translab.tantor.server.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.translab.tantor.server.service.ConfigQueryService;
import io.translab.tantor.server.service.ConfigMutationService;
import io.translab.tantor.server.util.RoleAuthenticationUtil;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/clusters/{clusterId}/config")
@RequiredArgsConstructor
public class ConfigController {

    private final ConfigQueryService configQueryService;
    private final ConfigMutationService configMutationService;
    private final RoleAuthenticationUtil roleAuthenticationUtil;

    private ResponseEntity<Map<String, Object>> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "Unauthorized"));
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> getBrokerConfigs(@PathVariable UUID clusterId) {
        return configQueryService.getBrokerConfigs(clusterId);
    }

    @PostMapping("/rolling-apply")
    public ResponseEntity<Map<String, Object>> rollingApply(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable UUID clusterId,
            @RequestBody Map<String, Object> payload) {
        if (!roleAuthenticationUtil.canAccess(authorization, RoleAuthenticationUtil.CONFIGURATION_CHANGE)) {
            return unauthorized();
        }
        return configMutationService.rollingApply(authorization, clusterId, payload);
    }

    @Deprecated
    @PutMapping("/unsafe-legacy/services/{serviceId}")
    public ResponseEntity<?> updateServiceConfig(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable UUID clusterId,
            @PathVariable UUID serviceId,
            @RequestBody ServiceConfigUpdateRequest request
    ) throws JsonProcessingException {
        if (!roleAuthenticationUtil.canAccess(authorization, RoleAuthenticationUtil.CONFIGURATION_CHANGE)) {
            return unauthorized();
        }
        return configMutationService.updateServiceConfig(authorization, clusterId, serviceId, request);
    }

    @PutMapping("/bulk")
    public ResponseEntity<?> updateConfigBulk(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable UUID clusterId,
            @RequestBody BulkConfigRequest request) throws JsonProcessingException {
        if (!roleAuthenticationUtil.canAccess(authorization, RoleAuthenticationUtil.CONFIGURATION_CHANGE)) {
            return unauthorized();
        }
        return configMutationService.updateConfigBulk(authorization, clusterId, request);
    }

    @PostMapping("/read")
    public ResponseEntity<Map<String, Object>> readConfig(@PathVariable UUID clusterId, @RequestBody Map<String, Object> request) {
        return configQueryService.readConfig(clusterId, request);
    }

    @Data
    public static class BulkConfigRequest {
        private String configKey;
        private String configValue;
        private boolean applyToAgents = false;
        private boolean restart = false;
    }

    @Data
    public static class ServiceConfigUpdateRequest {
        private Map<String, Object> properties;
        private boolean restart = true;
    }
}
