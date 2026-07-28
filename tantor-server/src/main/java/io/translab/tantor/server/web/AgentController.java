package io.translab.tantor.server.web;

import io.translab.tantor.server.dto.HostHeartbeatDto;
import io.translab.tantor.server.dto.HostRegistrationDto;
import io.translab.tantor.server.dto.TaskDto;
import io.translab.tantor.server.dto.TaskResultDto;
import io.translab.tantor.server.service.AgentService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@org.springframework.validation.annotation.Validated
@RestController
@RequestMapping("/api/v1/agents")
@RequiredArgsConstructor
@PreAuthorize("hasRole('AGENT')")
public class AgentController {

    private final AgentService agentService;

    @PostMapping("/register")
    public ResponseEntity<Void> registerHost(@RequestBody HostRegistrationDto registrationDto, HttpServletRequest request) {
        requireAgentIdentity(registrationDto.getHostId());
        if (!agentService.registerHost(registrationDto, sourceIp(request))) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        return ResponseEntity.ok().build();
    }

    @PostMapping("/heartbeat")
    public ResponseEntity<Void> heartbeat(@RequestBody HostHeartbeatDto heartbeatDto, HttpServletRequest request) {
        requireAgentIdentity(heartbeatDto.getHostId());
        AgentService.HeartbeatResult result = agentService.processHeartbeat(heartbeatDto, sourceIp(request));
        if (result == AgentService.HeartbeatResult.ACCEPTED) {
            return ResponseEntity.ok().build();
        }
        if (result == AgentService.HeartbeatResult.SOURCE_MISMATCH) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        return ResponseEntity.notFound().build();
    }

    @GetMapping("/{hostId}/tasks")
    public ResponseEntity<List<TaskDto>> pollTasks(@PathVariable String hostId) {
        requireAgentIdentity(hostId);
        List<TaskDto> tasks = agentService.getPendingTasks(hostId);
        if (tasks.isEmpty()) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(tasks);
    }

    @PostMapping("/tasks/result")
    public ResponseEntity<Void> reportTaskResult(@RequestBody TaskResultDto resultDto) {
        requireAgentIdentity(resultDto.getHostId());
        agentService.processTaskResult(resultDto);
        return ResponseEntity.ok().build();
    }

    private void requireAgentIdentity(String requestHostId) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || requestHostId == null
                || !requestHostId.equals(authentication.getName())) {
            throw new AccessDeniedException("Agent certificate identity does not match hostId");
        }
    }

    private String sourceIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}
