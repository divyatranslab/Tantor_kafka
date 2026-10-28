package io.translab.tantor.server.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.translab.tantor.server.domain.Job;
import io.translab.tantor.server.domain.JobStatus;
import io.translab.tantor.server.domain.JobType;
import io.translab.tantor.server.dto.JobSummaryDto;
import io.translab.tantor.server.dto.PaginatedResponse;
import io.translab.tantor.server.repository.JobRepository;
import io.translab.tantor.server.repository.JobStepRepository;
import io.translab.tantor.server.service.JobService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class JobControllerPaginationTest {

    private JobRepository jobRepository;
    private JobStepRepository jobStepRepository;
    private ObjectMapper objectMapper;
    private JobService jobService;
    private JobController jobController;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        jobRepository = mock(JobRepository.class);
        jobStepRepository = mock(JobStepRepository.class);
        objectMapper = new ObjectMapper();
        jobService = new JobService(jobRepository, jobStepRepository, objectMapper);
        jobController = new JobController(jobService);
        mockMvc = MockMvcBuilders.standaloneSetup(jobController)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private Job createFullJob(UUID id, Instant createdAt) {
        Job job = new Job();
        job.setId(id);
        job.setType(JobType.DEPLOYMENT);
        job.setStatus(JobStatus.SUCCESS);
        job.setRequestedBy("admin");
        job.setResourceKey("cluster-alpha");
        job.setStartTime(createdAt.plusSeconds(1));
        job.setEndTime(createdAt.plusSeconds(30));
        job.setPayload("{\"clusterId\":\"" + UUID.randomUUID() + "\",\"secret\":\"very-sensitive-and-large\"}");
        job.setProgress("{\"currentStep\":5,\"totalSteps\":5}");
        job.setLogs("[2026-10-28] Step 1 done\n[2026-10-28] Step 2 done\nVery large log output...");
        job.setRetryCount(0);
        job.setRollbackSupported(true);
        job.setCreatedAt(createdAt);
        job.setUpdatedAt(createdAt.plusSeconds(30));
        return job;
    }

    private JobSummaryDto createSummaryDto(UUID id, Instant createdAt) {
        return JobSummaryDto.builder()
                .id(id)
                .type(JobType.DEPLOYMENT)
                .status(JobStatus.SUCCESS)
                .requestedBy("admin")
                .resourceKey("cluster-alpha")
                .startTime(createdAt.plusSeconds(1))
                .endTime(createdAt.plusSeconds(30))
                .progress("{\"currentStep\":5,\"totalSteps\":5}")
                .retryCount(0)
                .rollbackSupported(true)
                .createdAt(createdAt)
                .updatedAt(createdAt.plusSeconds(30))
                .build();
    }

    @Test
    @DisplayName("Scenario 1 — Default request returns bounded page of jobs with default page size 20")
    void defaultRequestReturnsBoundedPageOfJobs() throws Exception {
        List<JobSummaryDto> sampleList = new ArrayList<>();
        Instant baseTime = Instant.parse("2026-10-28T10:00:00Z");
        for (int i = 0; i < 20; i++) {
            sampleList.add(createSummaryDto(UUID.randomUUID(), baseTime.minusSeconds(i)));
        }
        Page<JobSummaryDto> page = new PageImpl<>(sampleList, PageRequest.of(0, 20), 45);
        when(jobRepository.findAllJobSummaries(any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get("/api/v1/ui/jobs")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(45))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$.content.length()").value(20));

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(jobRepository).findAllJobSummaries(pageableCaptor.capture());
        Pageable requestedPageable = pageableCaptor.getValue();
        assertThat(requestedPageable.getPageNumber()).isEqualTo(0);
        assertThat(requestedPageable.getPageSize()).isEqualTo(20);
    }

    @Test
    @DisplayName("Scenario 2 & 3 — Custom page and size return the correct subset and metadata")
    void customPageAndSizeReturnCorrectSubsetAndMetadata() throws Exception {
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-10-28T10:15:00Z");
        JobSummaryDto summary = createSummaryDto(id, createdAt);

        Page<JobSummaryDto> page = new PageImpl<>(List.of(summary), PageRequest.of(2, 5), 11);
        when(jobRepository.findAllJobSummaries(any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get("/api/v1/ui/jobs?page=2&size=5")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.totalElements").value(11))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(id.toString()));

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(jobRepository).findAllJobSummaries(captor.capture());
        assertThat(captor.getValue().getPageNumber()).isEqualTo(2);
        assertThat(captor.getValue().getPageSize()).isEqualTo(5);
    }

    @Test
    @DisplayName("Scenario 4 — Empty job history returns an empty page successfully")
    void emptyJobHistoryReturnsEmptyPage() throws Exception {
        Page<JobSummaryDto> emptyPage = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
        when(jobRepository.findAllJobSummaries(any(Pageable.class))).thenReturn(emptyPage);

        mockMvc.perform(get("/api/v1/ui/jobs")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    @DisplayName("Scenario 5 — Invalid page numbers and sizes return 400 Bad Request")
    void invalidPageNumbersAndSizesHandledProperly() throws Exception {
        mockMvc.perform(get("/api/v1/ui/jobs?page=-1&size=20"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("Page index must not be less than zero"));

        mockMvc.perform(get("/api/v1/ui/jobs?page=0&size=0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("Page size must be greater than zero"));

        mockMvc.perform(get("/api/v1/ui/jobs?page=0&size=-10"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("Page size must be greater than zero"));
    }

    @Test
    @DisplayName("Scenario 6 — Deterministic sorting orders by createdAt DESC, id DESC")
    void deterministicSortingOrdersByCreatedAtDescThenIdDesc() {
        when(jobRepository.findAllJobSummaries(any(Pageable.class)))
                .thenReturn(Page.empty());

        jobService.getJobs(0, 20);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(jobRepository).findAllJobSummaries(captor.capture());
        Sort sort = captor.getValue().getSort();

        Sort.Order createdAtOrder = sort.getOrderFor("createdAt");
        Sort.Order idOrder = sort.getOrderFor("id");

        assertThat(createdAtOrder).isNotNull();
        assertThat(createdAtOrder.getDirection()).isEqualTo(Sort.Direction.DESC);
        assertThat(idOrder).isNotNull();
        assertThat(idOrder.getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    @DisplayName("Scenario 7 — Summary list response excludes payloads, logs, and detailed steps")
    void summaryResponseExcludesPayloadsLogsAndSteps() throws Exception {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        JobSummaryDto summary = createSummaryDto(id, now);

        when(jobRepository.findAllJobSummaries(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(summary)));

        mockMvc.perform(get("/api/v1/ui/jobs")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(id.toString()))
                .andExpect(jsonPath("$.content[0].type").value("DEPLOYMENT"))
                .andExpect(jsonPath("$.content[0].status").value("SUCCESS"))
                .andExpect(jsonPath("$.content[0].requestedBy").value("admin"))
                .andExpect(jsonPath("$.content[0].startTime").exists())
                .andExpect(jsonPath("$.content[0].endTime").exists())
                .andExpect(jsonPath("$.content[0].progress").exists())
                .andExpect(jsonPath("$.content[0].retryCount").value(0))
                .andExpect(jsonPath("$.content[0].rollbackSupported").value(true))
                // Payload, logs, steps MUST NOT exist in summary
                .andExpect(jsonPath("$.content[0].payload").doesNotExist())
                .andExpect(jsonPath("$.content[0].logs").doesNotExist())
                .andExpect(jsonPath("$.content[0].steps").doesNotExist());
    }

    @Test
    @DisplayName("Scenario 8 & 9 — Detailed job endpoint continues returning payload, logs, and full details")
    void jobDetailEndpointContinuesReturningPayloadLogsAndFullDetails() throws Exception {
        UUID jobId = UUID.randomUUID();
        Job fullJob = createFullJob(jobId, Instant.now());
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(fullJob));

        mockMvc.perform(get("/api/v1/ui/jobs/{id}", jobId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(jobId.toString()))
                .andExpect(jsonPath("$.payload").exists())
                .andExpect(jsonPath("$.payload").value(fullJob.getPayload()))
                .andExpect(jsonPath("$.logs").exists())
                .andExpect(jsonPath("$.logs").value(fullJob.getLogs()))
                .andExpect(jsonPath("$.requestedBy").value("admin"))
                .andExpect(jsonPath("$.progress").value(fullJob.getProgress()));
    }

    @Test
    @DisplayName("Scenario 10 — List endpoint applies database-level pagination and does not load all jobs")
    void listEndpointAppliesDatabasePaginationWithoutLoadingAllJobs() {
        when(jobRepository.findAllJobSummaries(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(createSummaryDto(UUID.randomUUID(), Instant.now()))));

        PaginatedResponse<JobSummaryDto> response = jobService.getJobs(0, 10);

        assertThat(response).isNotNull();
        assertThat(response.getContent()).hasSize(1);
        // Verify database pageable query was invoked
        verify(jobRepository, times(1)).findAllJobSummaries(any(Pageable.class));
        // Verify unbounded queries were NEVER invoked
        verify(jobRepository, never()).findAll();
        verify(jobRepository, never()).findAllByOrderByCreatedAtDesc();
    }

    @Test
    @DisplayName("Validation — Size exceeding MAX_PAGE_SIZE (100) is safely bounded")
    void sizeExceedingMaxIsBounded() {
        when(jobRepository.findAllJobSummaries(any(Pageable.class)))
                .thenReturn(Page.empty());

        jobService.getJobs(0, 500);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(jobRepository).findAllJobSummaries(captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(JobService.MAX_PAGE_SIZE);
    }
}
