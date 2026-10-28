package io.translab.tantor.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.translab.tantor.server.domain.Job;
import io.translab.tantor.server.domain.JobStatus;
import io.translab.tantor.server.domain.JobType;
import io.translab.tantor.server.repository.JobRepository;
import io.translab.tantor.server.repository.JobStepRepository;
import io.translab.tantor.server.service.JobService.ClaimedJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class JobServiceTest {

    private JobRepository jobRepository;
    private JobStepRepository jobStepRepository;
    private ObjectMapper objectMapper;
    private JobService jobService;

    @BeforeEach
    void setUp() {
        jobRepository = mock(JobRepository.class);
        jobStepRepository = mock(JobStepRepository.class);
        objectMapper = new ObjectMapper();
        jobService = new JobService(jobRepository, jobStepRepository, objectMapper);
        when(jobRepository.save(any(Job.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private Job createJob(JobStatus status) {
        Job job = new Job();
        job.setId(UUID.randomUUID());
        job.setType(JobType.DEPLOYMENT);
        job.setStatus(status);
        job.setCreatedAt(Instant.now());
        return job;
    }

    private int getMaxNormalJobsBeforeRollback() {
        Integer threshold = (Integer) ReflectionTestUtils.getField(JobService.class, "MAX_NORMAL_JOBS_BEFORE_ROLLBACK");
        assertThat(threshold).isNotNull();
        return threshold;
    }

    private int getNormalJobsSinceRollback() {
        AtomicInteger counter = (AtomicInteger) ReflectionTestUtils.getField(jobService, "normalJobsSinceRollback");
        assertThat(counter).isNotNull();
        return counter.get();
    }

    @Test
    @DisplayName("Test A — Rollback is not starved when normal jobs continuously arrive")
    void testA_rollbackIsNotStarved() {
        int threshold = getMaxNormalJobsBeforeRollback();

        // Normal jobs continuously available
        when(jobRepository.findFirstByStatusOrderByCreatedAtAsc(JobStatus.PENDING))
                .thenAnswer(inv -> Optional.of(createJob(JobStatus.PENDING)));

        // Rollback job waiting
        Job waitingRollbackJob = createJob(JobStatus.ROLLBACK_PENDING);
        waitingRollbackJob.setEndTime(Instant.now().minusSeconds(30));
        when(jobRepository.findFirstByStatusOrderByCreatedAtAsc(JobStatus.ROLLBACK_PENDING))
                .thenReturn(Optional.of(waitingRollbackJob));

        // Claim normal jobs up to the threshold
        for (int i = 1; i <= threshold; i++) {
            Optional<ClaimedJob> claim = jobService.claimNextJob();
            assertThat(claim).isPresent();
            assertThat(claim.get().rollback()).as("Claim %d should be normal job", i).isFalse();
            assertThat(getNormalJobsSinceRollback()).isEqualTo(i);
        }

        // Next eligible claim must select the rollback job
        Optional<ClaimedJob> rollbackClaim = jobService.claimNextJob();
        assertThat(rollbackClaim).isPresent();
        assertThat(rollbackClaim.get().rollback()).as("Claim should be identified as a rollback").isTrue();
        assertThat(rollbackClaim.get().jobId()).isEqualTo(waitingRollbackJob.getId());
        assertThat(waitingRollbackJob.getStatus()).isEqualTo(JobStatus.ROLLING_BACK);
        assertThat(waitingRollbackJob.getEndTime()).isNull();
        verify(jobRepository).save(waitingRollbackJob);
    }

    @Test
    @DisplayName("Test B — Normal processing continues when rollback is unavailable")
    void testB_normalProcessingContinuesWhenRollbackUnavailable() {
        int threshold = getMaxNormalJobsBeforeRollback();

        // No rollback job available initially
        when(jobRepository.findFirstByStatusOrderByCreatedAtAsc(JobStatus.ROLLBACK_PENDING))
                .thenReturn(Optional.empty());

        // Normal jobs continuously available
        when(jobRepository.findFirstByStatusOrderByCreatedAtAsc(JobStatus.PENDING))
                .thenAnswer(inv -> Optional.of(createJob(JobStatus.PENDING)));

        // Reach threshold with normal jobs
        for (int i = 1; i <= threshold; i++) {
            Optional<ClaimedJob> claim = jobService.claimNextJob();
            assertThat(claim).isPresent();
            assertThat(claim.get().rollback()).isFalse();
        }
        assertThat(getNormalJobsSinceRollback()).isEqualTo(threshold);

        // Claim when threshold is reached but no rollback job is available
        Optional<ClaimedJob> normalClaimAfterThreshold = jobService.claimNextJob();
        assertThat(normalClaimAfterThreshold).isPresent();
        assertThat(normalClaimAfterThreshold.get().rollback()).isFalse();

        // Verify the counter behavior resets to 1 after claiming a normal job when threshold was reached
        assertThat(getNormalJobsSinceRollback())
                .as("Counter should reset to 1 when threshold was reached and normal job claimed")
                .isEqualTo(1);

        // Verify behavioral policy: with a rollback job now available, exactly (threshold - 1) more normal
        // claims are allowed before rollback is selected
        Job waitingRollbackJob = createJob(JobStatus.ROLLBACK_PENDING);
        when(jobRepository.findFirstByStatusOrderByCreatedAtAsc(JobStatus.ROLLBACK_PENDING))
                .thenReturn(Optional.of(waitingRollbackJob));

        for (int i = 2; i <= threshold; i++) {
            Optional<ClaimedJob> intermediateClaim = jobService.claimNextJob();
            assertThat(intermediateClaim).isPresent();
            assertThat(intermediateClaim.get().rollback())
                    .as("Claim at counter %d should still be normal job", i)
                    .isFalse();
            assertThat(getNormalJobsSinceRollback()).isEqualTo(i);
        }

        // On next claim, counter is at threshold so rollback job is claimed
        Optional<ClaimedJob> rollbackClaim = jobService.claimNextJob();
        assertThat(rollbackClaim).isPresent();
        assertThat(rollbackClaim.get().rollback()).isTrue();
        assertThat(rollbackClaim.get().jobId()).isEqualTo(waitingRollbackJob.getId());
    }

    @Test
    @DisplayName("Test C — Counter resets after rollback and starts a new fairness cycle")
    void testC_counterResetsAfterRollback() {
        int threshold = getMaxNormalJobsBeforeRollback();

        // Continuously available normal jobs
        when(jobRepository.findFirstByStatusOrderByCreatedAtAsc(JobStatus.PENDING))
                .thenAnswer(inv -> Optional.of(createJob(JobStatus.PENDING)));

        // Waiting rollback job
        Job rollbackJob1 = createJob(JobStatus.ROLLBACK_PENDING);
        Job rollbackJob2 = createJob(JobStatus.ROLLBACK_PENDING);
        when(jobRepository.findFirstByStatusOrderByCreatedAtAsc(JobStatus.ROLLBACK_PENDING))
                .thenReturn(Optional.of(rollbackJob1), Optional.of(rollbackJob2));

        // Reach threshold
        for (int i = 1; i <= threshold; i++) {
            jobService.claimNextJob();
        }

        // Claim rollback job
        Optional<ClaimedJob> rollbackClaim1 = jobService.claimNextJob();
        assertThat(rollbackClaim1).isPresent();
        assertThat(rollbackClaim1.get().rollback()).isTrue();

        // Verify counter reset to 0
        assertThat(getNormalJobsSinceRollback())
                .as("Counter should reset to 0 after claiming rollback")
                .isEqualTo(0);

        // Verify next sequence starts a fresh fairness cycle:
        // Exactly 'threshold' normal jobs are processed before next rollback is claimed
        for (int i = 1; i <= threshold; i++) {
            Optional<ClaimedJob> nextCycleClaim = jobService.claimNextJob();
            assertThat(nextCycleClaim).isPresent();
            assertThat(nextCycleClaim.get().rollback()).as("Cycle 2, job %d should be normal", i).isFalse();
            assertThat(getNormalJobsSinceRollback()).isEqualTo(i);
        }

        // And then the second rollback job is claimed
        Optional<ClaimedJob> rollbackClaim2 = jobService.claimNextJob();
        assertThat(rollbackClaim2).isPresent();
        assertThat(rollbackClaim2.get().rollback()).isTrue();
        assertThat(rollbackClaim2.get().jobId()).isEqualTo(rollbackJob2.getId());
        assertThat(getNormalJobsSinceRollback()).isEqualTo(0);
    }

    @Test
    @DisplayName("Test D — Rollback fallback when no normal jobs exist")
    void testD_rollbackFallbackWhenNoNormalJobsExist() {
        // No normal jobs
        when(jobRepository.findFirstByStatusOrderByCreatedAtAsc(JobStatus.PENDING))
                .thenReturn(Optional.empty());

        // Rollback job available
        Job rollbackJob = createJob(JobStatus.ROLLBACK_PENDING);
        when(jobRepository.findFirstByStatusOrderByCreatedAtAsc(JobStatus.ROLLBACK_PENDING))
                .thenReturn(Optional.of(rollbackJob));

        // Counter is 0 (below threshold)
        assertThat(getNormalJobsSinceRollback()).isEqualTo(0);

        Optional<ClaimedJob> claim = jobService.claimNextJob();
        assertThat(claim).isPresent();
        assertThat(claim.get().rollback()).isTrue();
        assertThat(claim.get().jobId()).isEqualTo(rollbackJob.getId());
        assertThat(rollbackJob.getStatus()).isEqualTo(JobStatus.ROLLING_BACK);
        assertThat(getNormalJobsSinceRollback()).isEqualTo(0);
    }

    @Test
    @DisplayName("Test E — Empty queue returns Optional.empty()")
    void testE_emptyQueue() {
        when(jobRepository.findFirstByStatusOrderByCreatedAtAsc(JobStatus.PENDING))
                .thenReturn(Optional.empty());
        when(jobRepository.findFirstByStatusOrderByCreatedAtAsc(JobStatus.ROLLBACK_PENDING))
                .thenReturn(Optional.empty());

        Optional<ClaimedJob> claim = jobService.claimNextJob();
        assertThat(claim).isEmpty();
        verify(jobRepository, never()).save(any());
    }

    @Test
    @DisplayName("Test F — Status and timestamp handling for normal and rollback jobs")
    void testF_statusAndTimestampHandling() {
        // 1. Normal job status and timestamps
        Job normalJob = createJob(JobStatus.PENDING);
        normalJob.setStartTime(null);
        normalJob.setEndTime(Instant.now().minusSeconds(120)); // simulated stale end time

        when(jobRepository.findFirstByStatusOrderByCreatedAtAsc(JobStatus.PENDING))
                .thenReturn(Optional.of(normalJob));
        when(jobRepository.findFirstByStatusOrderByCreatedAtAsc(JobStatus.ROLLBACK_PENDING))
                .thenReturn(Optional.empty());

        Instant beforeClaim = Instant.now().minusMillis(50);
        Optional<ClaimedJob> normalClaim = jobService.claimNextJob();
        Instant afterClaim = Instant.now().plusMillis(50);

        assertThat(normalClaim).isPresent();
        assertThat(normalClaim.get().rollback()).isFalse();
        assertThat(normalClaim.get().jobId()).isEqualTo(normalJob.getId());
        assertThat(normalJob.getStatus()).isEqualTo(JobStatus.IN_PROGRESS);
        assertThat(normalJob.getStartTime()).isNotNull();
        assertThat(normalJob.getStartTime()).isAfterOrEqualTo(beforeClaim).isBeforeOrEqualTo(afterClaim);
        assertThat(normalJob.getEndTime()).isNull();
        verify(jobRepository).save(normalJob);

        // 2. Rollback job status and timestamps
        Job rollbackJob = createJob(JobStatus.ROLLBACK_PENDING);
        rollbackJob.setStartTime(Instant.now().minusSeconds(600)); // original execution start time preserved
        rollbackJob.setEndTime(Instant.now().minusSeconds(60));    // failed job end time to be cleared

        when(jobRepository.findFirstByStatusOrderByCreatedAtAsc(JobStatus.PENDING))
                .thenReturn(Optional.empty());
        when(jobRepository.findFirstByStatusOrderByCreatedAtAsc(JobStatus.ROLLBACK_PENDING))
                .thenReturn(Optional.of(rollbackJob));

        Optional<ClaimedJob> rollbackClaim = jobService.claimNextJob();

        assertThat(rollbackClaim).isPresent();
        assertThat(rollbackClaim.get().rollback()).isTrue();
        assertThat(rollbackClaim.get().jobId()).isEqualTo(rollbackJob.getId());
        assertThat(rollbackJob.getStatus()).isEqualTo(JobStatus.ROLLING_BACK);
        assertThat(rollbackJob.getEndTime()).as("Rollback job endTime must be cleared").isNull();
        verify(jobRepository).save(rollbackJob);
    }
}
