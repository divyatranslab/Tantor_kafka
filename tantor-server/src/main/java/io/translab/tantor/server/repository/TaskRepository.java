package io.translab.tantor.server.repository;

import io.translab.tantor.server.domain.Task;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TaskRepository extends JpaRepository<Task, UUID> {
    List<Task> findByHostIdAndStatusOrderByCreatedAtAsc(String hostId, String status);
    List<Task> findByHostIdAndCommandOrderByCreatedAtDesc(String hostId, String command);
    List<Task> findByHostIdInOrderByCreatedAtDesc(List<String> hostIds);
    List<Task> findByClusterIdOrderByCreatedAtDesc(UUID clusterId);
    List<Task> findByClusterIdAndHostIdAndCommandOrderByCreatedAtDesc(UUID clusterId, String hostId, String command);
    List<Task> findAllByOrderByUpdatedAtDesc();
}
