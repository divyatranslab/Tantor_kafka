package io.translab.tantor.server.web;

import io.translab.tantor.server.domain.Job;
import io.translab.tantor.server.domain.JobStep;
import io.translab.tantor.server.domain.Task;
import io.translab.tantor.server.dto.JobStepProgressDto;
import io.translab.tantor.server.repository.TaskRepository;
import io.translab.tantor.server.service.JobService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/ui/jobs")
@RequiredArgsConstructor
public class JobController {

    private final JobService jobService;
    private final TaskRepository taskRepository;

    @GetMapping
    public ResponseEntity<List<Job>> listJobs() {
        return ResponseEntity.ok(jobService.getAllJobs());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Job> getJob(@PathVariable UUID id) {
        try {
            return ResponseEntity.ok(jobService.getJob(id));
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/{id}/steps")
    public ResponseEntity<List<JobStepProgressDto>> getJobSteps(@PathVariable UUID id) {
        try {
            jobService.getJob(id);
            List<JobStep> steps = jobService.getSteps(id);
            List<UUID> taskIds = steps.stream()
                    .map(JobStep::getAgentTaskId)
                    .filter(java.util.Objects::nonNull)
                    .toList();
            Map<UUID, Task> tasksById = taskRepository.findAllById(taskIds).stream()
                    .collect(Collectors.toMap(Task::getId, Function.identity()));
            return ResponseEntity.ok(steps.stream()
                    .map(step -> JobStepProgressDto.from(step, tasksById.get(step.getAgentTaskId())))
                    .toList());
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<?> retryJob(@PathVariable UUID id) {
        try {
            return ResponseEntity.ok(jobService.retryJob(id));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/rollback")
    public ResponseEntity<?> rollbackJob(@PathVariable UUID id) {
        try {
            return ResponseEntity.ok(jobService.requestRollback(id));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", e.getMessage()));
        }
    }
}
