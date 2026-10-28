package io.translab.tantor.server.repository;

import io.translab.tantor.server.domain.Job;
import io.translab.tantor.server.domain.JobStatus;
import io.translab.tantor.server.dto.JobSummaryDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface JobRepository extends JpaRepository<Job, UUID> {
    List<Job> findByStatus(JobStatus status);
    List<Job> findAllByOrderByCreatedAtDesc();

    @Query("SELECT new io.translab.tantor.server.dto.JobSummaryDto(" +

           "j.id, j.type, j.status, j.requestedBy, j.resourceKey, " +
           "j.startTime, j.endTime, j.progress, j.retryCount, j.rollbackSupported, " +
           "j.createdAt, j.updatedAt) " +
           "FROM Job j")
    Page<JobSummaryDto> findAllJobSummaries(Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Job> findFirstByStatusOrderByCreatedAtAsc(JobStatus status);
}
