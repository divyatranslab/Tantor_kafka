package io.translab.tantor.server.web;

import io.translab.tantor.server.dto.HostHeartbeatDto;
import io.translab.tantor.server.dto.HostRegistrationDto;
import io.translab.tantor.server.dto.TaskDto;
import io.translab.tantor.server.dto.TaskResultDto;
import io.translab.tantor.server.service.AgentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/agents")
@RequiredArgsConstructor
public class AgentController {
    
    private final AgentService agentService;

    @PostMapping("/register")
    public ResponseEntity<Void> registerHost(@RequestBody HostRegistrationDto registrationDto) {
        agentService.registerHost(registrationDto);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/heartbeat")
    public ResponseEntity<Void> heartbeat(@RequestBody HostHeartbeatDto heartbeatDto) {
        if (agentService.processHeartbeat(heartbeatDto)) {
            return ResponseEntity.ok().build();
        }
        return ResponseEntity.notFound().build();
    }

    @GetMapping("/{hostId}/tasks")
    public ResponseEntity<List<TaskDto>> pollTasks(@PathVariable String hostId) {
        List<TaskDto> tasks = agentService.getPendingTasks(hostId);
        if (tasks.isEmpty()) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(tasks);
    }

    @PostMapping("/tasks/result")
    public ResponseEntity<Void> reportTaskResult(@RequestBody TaskResultDto resultDto) {
        agentService.processTaskResult(resultDto);
        return ResponseEntity.ok().build();
    }
}
