package io.translab.tantor.server.service;

import io.translab.tantor.server.domain.Job;
import io.translab.tantor.server.domain.JobStatus;
import io.translab.tantor.server.audit.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class JobExecutor {

    private final JobService jobService;
    private final List<JobHandler> jobHandlers;
    private final AuditService auditService;

    @Async
    public void execute(UUID jobId, boolean rollback) {
        Job job = jobService.getJob(jobId);
        JobHandler handler = jobHandlers.stream()
                .filter(candidate -> candidate.supports(job.getType()))
                .findFirst()
                .orElse(null);

        if (handler == null) {
            fail(job, rollback, new IllegalStateException("No handler found for job type " + job.getType()));
            return;
        }

        jobService.appendLog(jobId, rollback ? "Rollback execution started." : "Job picked up by execution engine.");
        try {
            if (rollback) {
                handler.rollback(job);
                jobService.updateJobStatus(jobId, JobStatus.ROLLED_BACK);
                jobService.appendLog(jobId, "Rollback completed successfully.");
                auditCompletion(job, "ROLLBACK_SUCCEEDED", "SUCCESS");
            } else {
                handler.execute(job);
                jobService.updateJobStatus(jobId, JobStatus.SUCCESS);
                jobService.appendLog(jobId, "Job execution completed successfully.");
                auditCompletion(job, job.getType().name() + "_SUCCEEDED", "SUCCESS");
            }
        } catch (Exception e) {
            fail(job, rollback, e);
        }
    }

    private void fail(Job job, boolean rollback, Exception error) {
        log.error("{} failed for job {}", rollback ? "Rollback" : "Execution", job.getId(), error);
        JobStatus status = rollback
                ? JobStatus.ROLLBACK_FAILED
                : jobService.hasSuccessfulSteps(job.getId()) ? JobStatus.PARTIAL_SUCCESS : JobStatus.FAILED;
        jobService.updateJobStatus(job.getId(), status);
        jobService.appendLog(job.getId(), (rollback ? "Rollback failed: " : "Job execution failed: ") + error.getMessage());
        auditCompletion(job, rollback ? "ROLLBACK_FAILED" : job.getType().name() + "_FAILED", "FAILED");
    }

    private void auditCompletion(Job job, String action, String status) {
        if (job.getType() == io.translab.tantor.server.domain.JobType.ONBOARDING) {
            return;
        }
        auditService.recordAs(job.getRequestedBy(), "MANAGEMENT_SERVER", null,
                jobCategory(job), action, "JOB", job.getId().toString(), clusterId(job), status,
                Map.of("status", JobStatus.IN_PROGRESS.name()),
                Map.of("status", status, "type", job.getType().name()), null,
                Map.of("resourceKey", String.valueOf(job.getResourceKey())));
    }

    private String jobCategory(Job job) {
        return switch (job.getType()) {
            case DEPLOYMENT, ADD_HOST -> "DEPLOYMENT";
            case CONFIG_CHANGE, ROLLING_CONFIG_UPDATE -> "CONFIG_CHANGE";
            case ROLLING_RESTART -> "RESTART";
            case MONITORING_ENABLEMENT -> "MONITORING";
            case ONBOARDING -> "ONBOARDING";
        };
    }

    private UUID clusterId(Job job) {
        if (job.getResourceKey() == null || !job.getResourceKey().startsWith("cluster:")) return null;
        try { return UUID.fromString(job.getResourceKey().substring("cluster:".length())); }
        catch (Exception ignored) { return null; }
    }
}
