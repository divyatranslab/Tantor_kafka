package io.translab.tantor.server.monitoring;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.translab.tantor.server.repository.TaskRepository;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

@Service
public class MetricsService {
    
    private final MeterRegistry meterRegistry;
    private final TaskRepository taskRepository;

    public MetricsService(MeterRegistry meterRegistry, TaskRepository taskRepository) {
        this.meterRegistry = meterRegistry;
        this.taskRepository = taskRepository;
    }

    @PostConstruct
    public void init() {
        Gauge.builder("tantor.tasks.queued", taskRepository, 
                tr -> tr.countByStatus("PENDING"))
             .description("Number of tasks currently queued and waiting for an agent")
             .register(meterRegistry);
             
        Gauge.builder("tantor.tasks.failed", taskRepository, 
                tr -> tr.countByStatus("FAILED"))
             .description("Number of tasks that have failed permanently")
             .register(meterRegistry);
             
        Gauge.builder("tantor.tasks.in_progress", taskRepository, 
                tr -> tr.countByStatus("IN_PROGRESS"))
             .description("Number of tasks currently being processed by agents")
             .register(meterRegistry);
    }
}
