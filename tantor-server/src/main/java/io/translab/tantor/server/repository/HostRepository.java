package io.translab.tantor.server.repository;

import io.translab.tantor.server.domain.Host;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface HostRepository extends JpaRepository<Host, String> {
}
