package io.translab.tantor.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.translab.tantor.server.domain.ExternalCluster;
import io.translab.tantor.server.repository.ClusterRepository;
import io.translab.tantor.server.repository.ExternalClusterRepository;
import io.translab.tantor.server.repository.HostRepository;
import io.translab.tantor.server.security.EncryptionService;
import io.translab.tantor.server.security.TruststoreStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KafkaAdminServiceSecurityTest {

    @TempDir
    Path tempDir;

    @Test
    void buildsExpectedPropertiesForAllExternalSecurityModes() throws Exception {
        EncryptionService encryptionService = mock(EncryptionService.class);
        when(encryptionService.decrypt("encrypted-password")).thenReturn("broker-password");
        when(encryptionService.decrypt("encrypted-truststore-password")).thenReturn("changeit");

        KafkaAdminService service = new KafkaAdminService(
                mock(ClusterRepository.class),
                mock(ExternalClusterRepository.class),
                mock(HostRepository.class),
                new ObjectMapper(),
                encryptionService,
                mock(TruststoreStorageService.class));

        Properties plaintext = securityProperties(service, cluster("PLAINTEXT", null));
        assertThat(plaintext).containsEntry("security.protocol", "PLAINTEXT");
        assertThat(plaintext).doesNotContainKey("sasl.mechanism");
        assertThat(plaintext).doesNotContainKey("ssl.truststore.location");

        Path truststore = Files.writeString(tempDir.resolve("cluster.p12"), "test-store");
        Properties ssl = securityProperties(service, cluster("SSL", truststore));
        assertThat(ssl).containsEntry("security.protocol", "SSL");
        assertThat(ssl).containsEntry("ssl.truststore.location", truststore.toString());
        assertThat(ssl).containsEntry("ssl.truststore.password", "changeit");

        Properties saslPlaintext = securityProperties(service, cluster("SASL_PLAINTEXT", null));
        assertThat(saslPlaintext).containsEntry("security.protocol", "SASL_PLAINTEXT");
        assertThat(saslPlaintext).containsEntry("sasl.mechanism", "SCRAM-SHA-512");
        assertThat(String.valueOf(saslPlaintext.get("sasl.jaas.config")))
                .contains("ScramLoginModule", "username=\"admin\"", "password=\"broker-password\"");

        Properties saslSsl = securityProperties(service, cluster("SASL_SSL", truststore));
        assertThat(saslSsl).containsEntry("security.protocol", "SASL_SSL");
        assertThat(saslSsl).containsEntry("sasl.mechanism", "SCRAM-SHA-512");
        assertThat(saslSsl).containsEntry("ssl.truststore.location", truststore.toString());
        assertThat(String.valueOf(saslSsl.get("sasl.jaas.config")))
                .contains("ScramLoginModule", "username=\"admin\"", "password=\"broker-password\"");
    }

    @Test
    void classifiesKafkaAndPlatformManagedTopicsAsInternal() {
        KafkaAdminService service = new KafkaAdminService(
                mock(ClusterRepository.class),
                mock(ExternalClusterRepository.class),
                mock(HostRepository.class),
                new ObjectMapper(),
                mock(EncryptionService.class),
                mock(TruststoreStorageService.class));

        Set<String> internalTopics = Set.of(
                "__consumer_offsets", "_schemas", "connect-configs", "connect-offsets", "connect-status");

        internalTopics.forEach(topic -> assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                service, "isManagedInternalTopic", topic)).isTrue());
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                service, "isManagedInternalTopic", "customer-orders")).isFalse();
    }

    private Properties securityProperties(KafkaAdminService service, ExternalCluster cluster) {
        Properties properties = new Properties();
        ReflectionTestUtils.invokeMethod(service, "applySecurityProperties", properties, cluster, true);
        return properties;
    }

    private ExternalCluster cluster(String protocol, Path truststore) {
        ExternalCluster cluster = new ExternalCluster();
        cluster.setSecurityProtocol(protocol);
        if (protocol.startsWith("SASL_")) {
            cluster.setSaslMechanism("SCRAM-SHA-512");
            cluster.setSaslUsername("admin");
            cluster.setSaslPasswordEncrypted("encrypted-password");
        }
        if (protocol.endsWith("SSL")) {
            cluster.setTruststorePath(truststore.toString());
            cluster.setTruststoreType("PKCS12");
            cluster.setTruststoreContentEncrypted("encrypted-content");
            cluster.setTruststorePasswordEncrypted("encrypted-truststore-password");
        }
        return cluster;
    }
}
