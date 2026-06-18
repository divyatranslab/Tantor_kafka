package io.translab.tantor.server.repository;

import io.translab.tantor.server.domain.LdapConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface LdapConfigRepository extends JpaRepository<LdapConfig, UUID> {
}
