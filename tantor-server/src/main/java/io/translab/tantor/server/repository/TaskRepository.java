package io.translab.tantor.server.repository;

import io.translab.tantor.server.domain.Task;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TaskRepository extends JpaRepository<Task, UUID> {
    List<Task> findByHostIdAndStatusOrderByCreatedAtAsc(String hostId, String status);
}
